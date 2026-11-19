package com.loopers.job.ranking

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishStepConfiguration
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishTasklet
import com.loopers.batch.job.ranking.step.score.WeeklyRankingScoreStepConfiguration
import com.loopers.batch.job.ranking.step.snapshot.WeeklyRankingSnapshotStepConfiguration
import com.loopers.batch.job.ranking.step.snapshot.WeeklyRankingSnapshotTasklet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.scope.context.StepContext
import org.springframework.batch.item.ExecutionContext
import org.springframework.batch.test.MetaDataInstanceFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.jdbc.Sql
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@SpringBootTest(
    properties = [
        "spring.batch.job.enabled=false",
        "spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}",
        "ranking.chunk-size=2",
    ],
)
@ActiveProfiles("test")
@Sql("/db/weekly-ranking.sql")
class WeeklyRankingJobTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val launcher: JobLauncher,
    private val explorer: JobExplorer,
    private val snapshotTasklet: WeeklyRankingSnapshotTasklet,
    private val publishTasklet: WeeklyRankingPublishTasklet,
    private val transactionTemplate: TransactionTemplate,
    @param:Qualifier(WeeklyRankingJobConfig.JOB_NAME) private val job: Job,
) {
    @BeforeEach
    fun setUp() {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS purchase_activity (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              order_id BIGINT NOT NULL, product_id BIGINT NOT NULL, quantity INT NOT NULL,
              confirmed_at DATETIME(6) NOT NULL,
              UNIQUE KEY uk_purchase_order_product (order_id, product_id)
            ) ENGINE=InnoDB
            """.trimIndent(),
        )
    }

    @Test
    fun `UTC 주간 구간에 속한 구매만 스냅샷에 고정한다`() {
        val start = LocalDate.of(2026, 8, 17)
        purchase(41, 7, 10, start.atStartOfDay().minusNanos(1000))
        purchase(42, 7, 2, start.atStartOfDay())
        purchase(43, 9, 10, start.plusWeeks(1).atStartOfDay())

        launch(start, 1L)

        val orders = jdbc.queryForList(
            "SELECT order_id FROM weekly_ranking_input WHERE snapshot_id = ?",
            Long::class.javaObjectType,
            "weekly-2026-08-17-r1",
        )
        assertThat(orders).containsExactly(42L)
    }

    @Test
    fun `동점이면 상품 ID가 작은 상품을 먼저 공개한다`() {
        val start = LocalDate.of(2026, 8, 31)
        purchases(start, 50)

        launch(start, 1L)

        assertThat(results("weekly-2026-08-31-r1")).containsExactlyElementsOf(expectedResults)
    }

    @Test
    fun `점수 계산이 실패하면 마지막 체크포인트부터 이어간다`() {
        val start = LocalDate.of(2026, 9, 7)
        purchases(start, 100)
        val snapshotId = "weekly-2026-09-07-r1"

        val failed = failScoringProductNine(start, 1L)
        assertThat(scores(snapshotId)).containsExactly(7L to 5L, 8L to 1L)
        assertThat(savedReadCount(failed)).isEqualTo(2)

        val resumed = launch(start, 1L)

        assertThat(resumed.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(resumed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
        assertThat(resumed.id).isNotEqualTo(failed.id)
        assertThat(resumed.stepExecutions.map { it.stepName }).doesNotContain(WeeklyRankingSnapshotStepConfiguration.STEP_NAME)
        assertThat(scoreStep(resumed).readCount).isEqualTo(1)
        assertThat(scores(snapshotId)).containsExactly(7L to 5L, 8L to 1L, 9L to 5L)
    }

    @Test
    fun `재시작한 스냅샷의 결과는 정상 실행 결과와 같다`() {
        val start = LocalDate.of(2026, 9, 14)
        purchases(start, 110)
        val normalId = "weekly-2026-09-14-r1"
        val resumedId = "weekly-2026-09-14-r2"
        val expectedInputs = listOf(
            Input(110L, 7L, 2, start.atStartOfDay()),
            Input(110L, 8L, 1, start.atTime(1, 0)),
            Input(111L, 7L, 3, start.plusDays(1).atTime(1, 0)),
            Input(112L, 9L, 5, start.plusDays(2).atTime(1, 0)),
        )
        launch(start, 1L)
        assertThat(inputs(normalId)).containsExactlyElementsOf(expectedInputs)

        failScoringProductNine(start, 2L)
        purchase(113, 9, 2, start.plusDays(3).atStartOfDay())

        launch(start, 2L)

        assertThat(inputs(resumedId)).containsExactlyElementsOf(expectedInputs)
        assertThat(results(resumedId)).containsExactlyElementsOf(expectedResults)
        assertThat(results(resumedId)).isEqualTo(results(normalId))
    }

    @Test
    fun `늦은 구매는 새 리비전의 순위에 반영한다`() {
        val start = LocalDate.of(2026, 9, 21)
        purchases(start, 120)
        launch(start, 1L)
        purchase(123, 9, 2, start.plusDays(3).atStartOfDay())

        launch(start, 2L)

        assertThat(results("weekly-2026-09-21-r1")).containsExactlyElementsOf(expectedResults)
        assertThat(results("weekly-2026-09-21-r2")).containsExactly(
            Result(9, 7, 1),
            Result(7, 5, 2),
            Result(8, 1, 3),
        )
    }

    @Test
    fun `구매가 0건이면 스냅샷 Step만 실행하고 Job을 완료한다`() {
        val start = LocalDate.of(2026, 10, 5)

        val execution = launch(start, 1L)

        val snapshotExecution = execution.stepExecutions.single()
        assertThat(snapshotExecution.stepName).isEqualTo(WeeklyRankingSnapshotStepConfiguration.STEP_NAME)
        assertThat(snapshotExecution.exitStatus.exitCode).isEqualTo("COMPLETED_NO_INPUT")
        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(scores("weekly-2026-10-05-r1")).isEmpty()
    }

    @Test
    fun `순위 공개에 실패하면 이전 순위를 유지하고 공개 Step부터 다시 실행한다`() {
        val start = LocalDate.of(2026, 10, 12)
        val previousId = "weekly-2026-10-12-r1"
        val revisedId = "weekly-2026-10-12-r2"
        purchases(start, 200)
        assertThat(launch(start, 1L).status).isEqualTo(BatchStatus.COMPLETED)

        failPublishingRevision(start, 2L)

        assertThat(results(revisedId)).isEmpty()
        assertThat(scores(revisedId)).hasSize(3)
        assertThat(results(previousId)).containsExactlyElementsOf(expectedResults)

        val resumed = launch(start, 2L)

        assertThat(resumed.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(resumed.stepExecutions.map { it.stepName }).containsExactly(WeeklyRankingPublishStepConfiguration.STEP_NAME)
        assertThat(results(revisedId)).containsExactlyElementsOf(expectedResults)
    }

    @Test
    fun `오래된 리비전은 최신 리비전 결과를 덮지 않는다`() {
        val start = LocalDate.of(2026, 10, 19)
        purchases(start, 300)
        launch(start, 2L)
        purchase(303, 9, 2, start.plusDays(3).atStartOfDay())

        val older = launch(start, 1L)

        assertThat(older.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(results("weekly-2026-10-19-r1").first()).isEqualTo(Result(9, 7, 1))
        assertThat(results("weekly-2026-10-19-r2")).containsExactlyElementsOf(expectedResults)
    }

    @Test
    fun `이미 공개된 스냅샷은 순위를 다시 쓰지 않는다`() {
        val period = WeeklyRankingPeriod(LocalDate.of(2026, 11, 2), 1L)
        val published = Result(7, 5, 1)
        jdbc.update(
            "INSERT INTO weekly_ranking_result (snapshot_id, product_id, score, rank_position) VALUES (?, ?, ?, ?)",
            period.snapshotId,
            published.productId,
            published.score,
            published.rank,
        )
        val step = MetaDataInstanceFactory.createStepExecution(parameters(period.start, period.revision))
        val contribution = step.createStepContribution()

        transactionTemplate.executeWithoutResult {
            publishTasklet.execute(contribution, ChunkContext(StepContext(step)))
        }

        assertThat(contribution.writeCount).isZero()
        assertThat(results(period.snapshotId)).containsExactly(published)
    }

    @Test
    fun `순위가 공개된 작업의 입력은 다음 신규 작업에서 정리한다`() {
        val start = LocalDate.of(2026, 11, 9)
        val completedId = "weekly-2026-11-09-r1"
        purchases(start, 400)
        assertThat(launch(start, 1L).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(inputCount(completedId)).isEqualTo(4L)

        launch(start, 2L)

        assertThat(inputCount(completedId)).isZero()
        assertThat(results(completedId)).containsExactlyElementsOf(expectedResults)
    }

    @Test
    fun `신규 작업이 시작돼도 실패한 작업의 입력은 남긴다`() {
        val start = LocalDate.of(2026, 11, 16)
        purchases(start, 450)
        failScoringProductNine(start, 1L)

        assertThat(launch(start, 2L).status).isEqualTo(BatchStatus.COMPLETED)

        assertThat(inputCount("weekly-2026-11-16-r1")).isEqualTo(4L)
    }

    @Test
    fun `구매 0건으로 확정한 스냅샷은 다시 실행해도 비어 있다`() {
        val period = WeeklyRankingPeriod(LocalDate.of(2026, 11, 23), 1L)
        val jobParameters = parameters(period.start, period.revision)
        val initialStep = MetaDataInstanceFactory.createStepExecution(jobParameters)

        transactionTemplate.executeWithoutResult {
            snapshotTasklet.execute(initialStep.createStepContribution(), ChunkContext(StepContext(initialStep)))
        }
        purchase(500, 7, 1, period.start.atStartOfDay())
        val restartedStep = MetaDataInstanceFactory.createStepExecution(jobParameters, ExecutionContext(initialStep.executionContext))
        val contribution = restartedStep.createStepContribution()

        transactionTemplate.executeWithoutResult {
            snapshotTasklet.execute(contribution, ChunkContext(StepContext(restartedStep)))
        }

        assertThat(contribution.exitStatus.exitCode).isEqualTo(WeeklyRankingSnapshotTasklet.NO_INPUT_EXIT_CODE)
        assertThat(inputCount(period.snapshotId)).isZero()
    }

    private fun launch(start: LocalDate, revision: Long): JobExecution = launcher.run(job, parameters(start, revision))

    private fun parameters(start: LocalDate, revision: Long) = JobParametersBuilder()
        .addLocalDate("periodStart", start)
        .addLong("revision", revision)
        .toJobParameters()

    private fun scoreStep(execution: JobExecution) =
        execution.stepExecutions.single { it.stepName == WeeklyRankingScoreStepConfiguration.STEP_NAME }

    private fun savedReadCount(execution: JobExecution): Int {
        val step = scoreStep(execution)
        val stored = requireNotNull(explorer.getStepExecution(requireNotNull(execution.id), requireNotNull(step.id)))
        return stored.executionContext.getInt("WEEKLY_RANKING_PRODUCT_READER.read.count")
    }

    private fun inputs(snapshotId: String): List<Input> =
        jdbc.query(
            """
            SELECT order_id, product_id, quantity, confirmed_at
            FROM weekly_ranking_input
            WHERE snapshot_id = ?
            ORDER BY order_id, product_id
            """.trimIndent(),
            { row, _ ->
                Input(
                    row.getLong("order_id"),
                    row.getLong("product_id"),
                    row.getInt("quantity"),
                    row.getObject("confirmed_at", LocalDateTime::class.java),
                )
            },
            snapshotId,
        )

    private fun inputCount(snapshotId: String): Long =
        requireNotNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_input WHERE snapshot_id = ?", Long::class.javaObjectType, snapshotId),
        )

    private fun scores(snapshotId: String): List<Pair<Long, Long>> =
        jdbc.query(
            "SELECT product_id, score FROM weekly_ranking_result WHERE snapshot_id = ? ORDER BY product_id",
            { row, _ -> row.getLong("product_id") to row.getLong("score") },
            snapshotId,
        )

    private fun results(snapshotId: String): List<Result> =
        jdbc.query(
            "SELECT product_id, score, rank_position FROM weekly_ranking_result " +
                "WHERE snapshot_id = ? AND rank_position IS NOT NULL ORDER BY rank_position",
            { row, _ -> Result(row.getLong("product_id"), row.getLong("score"), row.getLong("rank_position")) },
            snapshotId,
        )

    private fun purchases(start: LocalDate, orderId: Long) {
        purchase(orderId, 7, 2, start.atStartOfDay())
        purchase(orderId, 8, 1, start.atTime(1, 0))
        purchase(orderId + 1, 7, 3, start.plusDays(1).atTime(1, 0))
        purchase(orderId + 2, 9, 5, start.plusDays(2).atTime(1, 0))
    }

    private fun purchase(orderId: Long, productId: Long, quantity: Int, confirmedAt: LocalDateTime) {
        jdbc.update(
            "INSERT INTO purchase_activity (order_id, product_id, quantity, confirmed_at) VALUES (?, ?, ?, ?)",
            orderId,
            productId,
            quantity,
            confirmedAt,
        )
    }

    private fun withCheck(table: String, name: String, condition: String, action: () -> JobExecution): JobExecution {
        jdbc.execute("ALTER TABLE $table ADD CONSTRAINT $name CHECK ($condition)")
        return try {
            action()
        } finally {
            jdbc.execute("ALTER TABLE $table DROP CHECK $name")
        }
    }

    private fun failScoringProductNine(start: LocalDate, revision: Long): JobExecution {
        val snapshotId = WeeklyRankingPeriod(start, revision).snapshotId
        val execution = withCheck("weekly_ranking_result", "reject_product_nine", "snapshot_id <> '$snapshotId' OR product_id <> 9") {
            launch(start, revision)
        }
        assertThat(scoreStep(execution).status).isEqualTo(BatchStatus.FAILED)
        return execution
    }

    private fun failPublishingRevision(start: LocalDate, revision: Long) {
        val snapshotId = WeeklyRankingPeriod(start, revision).snapshotId
        val execution = withCheck("weekly_ranking_result", "reject_rank", "snapshot_id <> '$snapshotId' OR rank_position IS NULL") {
            launch(start, revision)
        }
        val publishStep = execution.stepExecutions.single { it.stepName == WeeklyRankingPublishStepConfiguration.STEP_NAME }
        assertThat(publishStep.status).isEqualTo(BatchStatus.FAILED)
    }

    private data class Input(val orderId: Long, val productId: Long, val quantity: Int, val confirmedAt: LocalDateTime)

    private data class Result(val productId: Long, val score: Long, val rank: Long)

    private companion object {
        val expectedResults = listOf(Result(7, 5, 1), Result(9, 5, 2), Result(8, 1, 3))
    }
}
