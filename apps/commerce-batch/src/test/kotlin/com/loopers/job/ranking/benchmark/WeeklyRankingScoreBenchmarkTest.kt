package com.loopers.job.ranking.benchmark

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.benchmark.step.score.PurchaseRow
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.PURCHASE_READER
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration.Companion.CHUNK_SIZE
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishStepConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.Step
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.io.path.writeText
import kotlin.time.measureTimedValue

@SpringJUnitConfig(WeeklyRankingScoreBenchmarkConfig::class)
@ActiveProfiles(RankingBenchmarkScoreStepConfiguration.PROFILE)
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}"])
class WeeklyRankingScoreBenchmarkTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val launcher: JobLauncher,
    private val explorer: JobExplorer,
    private val jobRepository: JobRepository,
    private val steps: Map<String, Step>,
    private val dataSource: DataSource,
    private val benchmarkMySql: MySQLContainer<*>,
    private val jdbcCounter: BenchmarkJdbcCounter,
    @param:Qualifier(WeeklyRankingPublishStepConfiguration.STEP_NAME) private val publishStep: Step,
) {
    @Test
    fun `다섯 방식에서 동일한 점수와 동점 순위를 공개한다`() {
        val fixture = fixture(1L, overlappingPurchases())
        val expected = listOf(RankedScore(7L, 204L, 1L), RankedScore(8L, 204L, 2L), RankedScore(9L, 3L, 3L))

        for (method in RankingBenchmarkMethod.entries) {
            fixture.clearResults()
            val execution = launch(job(method, "correctness"), fixture.period)
            assertCompleted(execution)
            assertThat(fixture.results()).containsExactlyElementsOf(expected)
        }
    }

    @Test
    fun `집계 대상 상품이 101개일 때 마지막 청크의 상품도 저장한다`() {
        val fixture = fixture(2L, distinctPurchases(101))
        val expected = (101 downTo 1).mapIndexed { rank, productId ->
            RankedScore(productId.toLong(), productId.toLong(), rank + 1L)
        }

        for (method in listOf(RankingBenchmarkMethod.B, RankingBenchmarkMethod.C_CURSOR, RankingBenchmarkMethod.C_PAGING)) {
            fixture.clearResults()
            val execution = launch(job(method, "last_chunk"), fixture.period)
            assertCompleted(execution)
            assertThat(scoreExecution(execution, method).readCount).isEqualTo(101L)
            assertThat(fixture.results()).containsExactlyElementsOf(expected)
        }
    }

    @Test
    fun `구매 Row 방식은 롤백된 청크부터 재시작해 수량을 한 번씩 반영한다`() {
        val fixture = fixture(3L, overlappingPurchases())
        val method = RankingBenchmarkMethod.A
        val job = job(method, "resume")
        fixture.clearResults()

        val failed = withRejectedScore(fixture.period, productId = 8, maxScore = 100) { launch(job, fixture.period) }

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(fixture.results()).containsExactly(RankedScore(7L, 100L, null), RankedScore(8L, 100L, null))
        val stored = requireNotNull(
            explorer.getStepExecution(requireNotNull(failed.id), requireNotNull(scoreExecution(failed, method).id)),
        )
        assertThat(stored.executionContext.getInt("$PURCHASE_READER.read.count")).isEqualTo(CHUNK_SIZE)

        val resumed = launch(job, fixture.period)

        assertCompleted(resumed)
        assertThat(resumed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
        assertThat(scoreExecution(resumed, method).readCount).isEqualTo(105L)
        assertThat(fixture.resultCount()).isEqualTo(3L)
        assertThat(fixture.countResultMismatches()).isZero()
    }

    @Test
    fun `상품 ID와 집계 Reader는 마지막으로 커밋한 상품 다음부터 재시작한다`() {
        val fixture = fixture(4L, distinctPurchases(205))
        for (method in listOf(RankingBenchmarkMethod.B, RankingBenchmarkMethod.C_CURSOR, RankingBenchmarkMethod.C_PAGING)) {
            fixture.clearResults()
            val job = job(method, "resume")

            val failed = withRejectedScore(fixture.period, productId = 102, maxScore = 0) { launch(job, fixture.period) }

            assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
            assertThat(fixture.results()).containsExactlyElementsOf((1L..100L).map { RankedScore(it, it, null) })

            val resumed = launch(job, fixture.period)

            assertCompleted(resumed)
            assertThat(resumed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
            assertThat(scoreExecution(resumed, method).readCount).isEqualTo(105L)
            assertThat(fixture.resultCount()).isEqualTo(205L)
            assertThat(fixture.countResultMismatches()).isZero()
        }
    }

    @Test
    fun `SQL Tasklet은 집계 쓰기 전체를 롤백하고 같은 입력으로 다시 실행한다`() {
        val fixture = fixture(5L, distinctPurchases(205))
        val job = job(RankingBenchmarkMethod.D, "resume")
        fixture.clearResults()

        val failed = withRejectedScore(fixture.period, productId = 102, maxScore = 0) { launch(job, fixture.period) }

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(fixture.resultCount()).isZero()

        val resumed = launch(job, fixture.period)

        assertCompleted(resumed)
        assertThat(resumed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
        assertThat(fixture.resultCount()).isEqualTo(205L)
        assertThat(fixture.countResultMismatches()).isZero()
    }

    @Test
    fun `결과를 초기화한 새 Job은 같은 스냅샷을 중복 없이 처음부터 처리한다`() {
        val fixture = fixture(6L, overlappingPurchases())
        for (method in RankingBenchmarkMethod.entries) {
            fixture.clearResults()
            val first = launch(job(method, "first"), fixture.period)
            assertCompleted(first)
            val expected = fixture.results()

            fixture.clearResults()
            val replay = launch(job(method, "replay"), fixture.period)

            assertCompleted(replay)
            assertThat(replay.jobInstance.instanceId).isNotEqualTo(first.jobInstance.instanceId)
            assertThat(fixture.results()).containsExactlyElementsOf(expected)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_RANKING_RECOVERY_COMPARISON", matches = "true")
    fun `같은 구매 스냅샷에서 두 집계 방식의 재시작을 비교한다`() {
        val purchases = (1L..203L).map { PurchaseRow(it, 1) } + listOf(PurchaseRow(1L, 2), PurchaseRow(204L, 1))
        val fixture = fixture(7L, purchases)
        val measurements = listOf(RankingBenchmarkMethod.A, RankingBenchmarkMethod.B).map { method ->
            fixture.clearResults()
            val job = job(method, "article_recovery")

            val failed = withRejectedScore(fixture.period, productId = 102, maxScore = 0) { launch(job, fixture.period) }
            assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
            val committed = (1L..100L).map { productId ->
                val score = if (method == RankingBenchmarkMethod.B && productId == 1L) 3L else 1L
                RankedScore(productId, score, null)
            }
            assertThat(fixture.results()).containsExactlyElementsOf(committed)

            val (resumed, elapsed) = measureTimedValue { launch(job, fixture.period) }
            assertCompleted(resumed)
            assertThat(resumed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
            val readCount = scoreExecution(resumed, method).readCount
            assertThat(readCount).isEqualTo(if (method == RankingBenchmarkMethod.A) 105L else 104L)
            assertThat(fixture.results().first()).isEqualTo(RankedScore(1L, 3L, 1L))
            assertThat(fixture.resultCount()).isEqualTo(204L)
            assertThat(fixture.countResultMismatches()).isZero()
            "${method.name},100,$readCount,${elapsed.inWholeNanoseconds}"
        }
        val base = Path.of(System.getenv("RANKING_BENCHMARK_OUTPUT") ?: "build/weekly-ranking-benchmark")
        val output = Files.createTempDirectory(Files.createDirectories(base), "recovery-")
        output.resolve("recovery.csv").writeText(
            "method,committed_products,resumed_read_count,resume_job_ns\n" + measurements.joinToString("\n", postfix = "\n"),
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_RANKING_BENCHMARK", matches = "true")
    fun `같은 구매 스냅샷에서 집계 방식별 비용을 측정한다`() {
        RankingBenchmarkRunner(jdbc, dataSource, benchmarkMySql, jdbcCounter).run(
            scoreSteps = RankingBenchmarkMethod.entries.associateWith { steps.getValue(it.stepName) },
            launch = { method, scoreStep, runName, period -> launch(job(method, runName, scoreStep), period) },
        )
    }

    private fun fixture(revision: Long, purchases: List<PurchaseRow>): RankingBenchmarkFixture =
        RankingBenchmarkFixture(jdbc, WeeklyRankingPeriod(PERIOD_START, revision)).apply {
            seed(purchases.asSequence())
            assertThat(freeze()).isEqualTo(purchases.size)
        }

    private fun overlappingPurchases(): List<PurchaseRow> =
        List(204) { PurchaseRow(if (it % 2 == 0) 7L else 8L, 2) } + PurchaseRow(9L, 3)

    private fun distinctPurchases(size: Int): List<PurchaseRow> =
        (1..size).map { PurchaseRow(it.toLong(), it) }

    private fun job(method: RankingBenchmarkMethod, runName: String, scoreStep: Step = steps.getValue(method.stepName)): Job =
        JobBuilder("RANKING_BENCHMARK_${method.name}_$runName", jobRepository)
            .start(scoreStep)
            .next(publishStep)
            .build()

    private fun launch(job: Job, period: WeeklyRankingPeriod): JobExecution = launcher.run(
        job,
        JobParametersBuilder().addLocalDate("periodStart", period.start).addLong("revision", period.revision).toJobParameters(),
    )

    private fun withRejectedScore(
        period: WeeklyRankingPeriod,
        productId: Int,
        maxScore: Int,
        launch: () -> JobExecution,
    ): JobExecution {
        jdbc.execute(
            "ALTER TABLE weekly_ranking_result ADD CONSTRAINT benchmark_reject_score " +
                "CHECK (snapshot_id <> '${period.snapshotId}' OR product_id <> $productId OR score <= $maxScore)",
        )
        return try {
            launch()
        } finally {
            jdbc.execute("ALTER TABLE weekly_ranking_result DROP CHECK benchmark_reject_score")
        }
    }

    companion object {
        internal val PERIOD_START = LocalDate.of(2040, 1, 2)
    }
}

internal enum class RankingBenchmarkMethod(val stepName: String) {
    A(RankingBenchmarkScoreStepConfiguration.PURCHASE_ROW_STEP),
    B(RankingBenchmarkScoreStepConfiguration.PRODUCT_ID_STEP),
    C_CURSOR(RankingBenchmarkScoreStepConfiguration.SCORE_CURSOR_STEP),
    C_PAGING(RankingBenchmarkScoreStepConfiguration.SCORE_PAGING_STEP),
    D(RankingBenchmarkScoreStepConfiguration.SQL_TASKLET_STEP),
}

internal fun scoreExecution(execution: JobExecution, method: RankingBenchmarkMethod) =
    execution.stepExecutions.single { it.stepName == method.stepName }

internal fun assertCompleted(execution: JobExecution) {
    assertThat(execution.status).describedAs("%s 실행 결과 %s", execution.jobInstance.jobName, execution.allFailureExceptions)
        .isEqualTo(BatchStatus.COMPLETED)
}
