package com.loopers.job.ranking.step.score

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.step.score.WeeklyProductScore
import com.loopers.batch.job.ranking.step.score.WeeklyRankingProcessListener
import com.loopers.batch.job.ranking.step.score.WeeklyRankingScoreStepConfiguration
import com.loopers.batch.job.ranking.step.snapshot.WeeklyRankingSnapshotStepConfiguration
import com.loopers.batch.listener.StepMonitorListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.jdbc.Sql
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate

@SpringBootTest(
    properties = [
        "spring.batch.job.enabled=false",
        "spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}",
    ],
)
@ActiveProfiles("test")
@Sql("/db/weekly-ranking.sql")
class WeeklyRankingRetryTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val launcher: JobLauncher,
    @param:Qualifier(WeeklyRankingSnapshotStepConfiguration.STEP_NAME) private val snapshotStep: Step,
    private val reader: JdbcCursorItemReader<Long>,
    private val writer: ItemWriter<WeeklyProductScore>,
    private val stepListener: StepMonitorListener,
    private val processListener: WeeklyRankingProcessListener,
) {
    private val periodStart = LocalDate.of(2030, 1, 7)

    @BeforeEach
    fun setUp() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS purchase_activity (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, " +
                "product_id BIGINT NOT NULL, quantity INT NOT NULL, confirmed_at DATETIME(6) NOT NULL)",
        )
        jdbc.update(
            "INSERT INTO purchase_activity (order_id, product_id, quantity, confirmed_at) VALUES (?, ?, ?, ?)",
            998877L,
            112233L,
            1,
            periodStart.atStartOfDay(),
        )
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM purchase_activity WHERE order_id = ?", 998877L)
    }

    @Test
    fun `일시적인 처리 오류는 최대 세 번 시도해 성공할 수 있다`() {
        val period = WeeklyRankingPeriod(periodStart, 1L)
        var attempts = 0
        val processor = ItemProcessor<Long, WeeklyProductScore> { productId ->
            attempts++
            if (attempts < 3) throw TransientDataAccessResourceException("일시적인 DB 오류")
            WeeklyProductScore(period.snapshotId, productId, 1)
        }

        val status = runStep(period, processor, writer)

        assertThat(status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `일시적인 처리 오류가 계속되면 세 번 시도한 뒤 실패한다`() {
        var attempts = 0
        val processor = ItemProcessor<Long, WeeklyProductScore> {
            attempts++
            throw TransientDataAccessResourceException("일시적인 DB 오류")
        }

        val status = runStep(WeeklyRankingPeriod(periodStart, 2L), processor, writer)

        assertThat(status).isEqualTo(BatchStatus.FAILED)
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `일시적이지 않은 처리 오류는 재시도하지 않는다`() {
        var attempts = 0
        val processor = ItemProcessor<Long, WeeklyProductScore> {
            attempts++
            throw IllegalArgumentException("잘못된 데이터")
        }

        val status = runStep(WeeklyRankingPeriod(periodStart, 3L), processor, writer)

        assertThat(status).isEqualTo(BatchStatus.FAILED)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `일시적인 쓰기 오류도 재시도한다`() {
        val period = WeeklyRankingPeriod(periodStart, 4L)
        var attempts = 0
        val processor = ItemProcessor<Long, WeeklyProductScore> { productId ->
            WeeklyProductScore(period.snapshotId, productId, 1)
        }
        val retryingWriter = ItemWriter<WeeklyProductScore> { items ->
            attempts++
            if (attempts < 3) throw TransientDataAccessResourceException("일시적인 DB 오류")
            writer.write(items)
        }

        val status = runStep(period, processor, retryingWriter)

        assertThat(status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(attempts).isEqualTo(3)
    }

    private fun runStep(
        period: WeeklyRankingPeriod,
        processor: ItemProcessor<Long, WeeklyProductScore>,
        itemWriter: ItemWriter<WeeklyProductScore>,
    ): BatchStatus {
        val step = WeeklyRankingScoreStepConfiguration().calculateWeeklyScores(
            jobRepository,
            transactionManager,
            reader,
            processor,
            itemWriter,
            stepListener,
            processListener,
            1,
        )
        val job = JobBuilder("WEEKLY_RANKING_RETRY_TEST_JOB", jobRepository).start(snapshotStep).next(step).build()
        val parameters = JobParametersBuilder()
            .addLocalDate("periodStart", period.start)
            .addLong("revision", period.revision)
            .toJobParameters()
        return launcher.run(job, parameters).status
    }
}
