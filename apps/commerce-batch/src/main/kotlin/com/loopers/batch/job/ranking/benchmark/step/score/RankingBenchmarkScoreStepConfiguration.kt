package com.loopers.batch.job.ranking.benchmark.step.score

import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.PURCHASE_READER
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.SCORE_CURSOR_READER
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.SCORE_PAGING_READER
import com.loopers.batch.job.ranking.step.score.WeeklyProductScore
import com.loopers.batch.job.ranking.step.score.WeeklyRankingReaderConfiguration
import com.loopers.batch.job.ranking.step.score.WeeklyRankingWriter
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemStreamReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.JdbcPagingItemReader
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
@Profile(RankingBenchmarkScoreStepConfiguration.PROFILE)
class RankingBenchmarkScoreStepConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val stepMonitorListener: StepMonitorListener,
) {
    @Bean(PURCHASE_ROW_STEP)
    fun purchaseRowScoreStep(
        @Qualifier(PURCHASE_READER) reader: JdbcPagingItemReader<PurchaseRow>,
        @Qualifier("purchaseRowChunkWriter") writer: ItemWriter<PurchaseRow>,
    ): Step = chunkStep(PURCHASE_ROW_STEP, reader, writer)

    @Bean(PRODUCT_ID_STEP)
    fun productIdScoreStep(
        @Qualifier(WeeklyRankingReaderConfiguration.READER_NAME) reader: JdbcCursorItemReader<Long>,
        @Qualifier("productIdChunkWriter") writer: ItemWriter<Long>,
    ): Step = chunkStep(PRODUCT_ID_STEP, reader, writer)

    @Bean(SCORE_CURSOR_STEP)
    fun cursorScoreStep(
        @Qualifier(SCORE_CURSOR_READER) reader: JdbcCursorItemReader<WeeklyProductScore>,
        writer: WeeklyRankingWriter,
    ): Step = chunkStep(SCORE_CURSOR_STEP, reader, writer)

    @Bean(SCORE_PAGING_STEP)
    fun pagingScoreStep(
        @Qualifier(SCORE_PAGING_READER) reader: JdbcPagingItemReader<WeeklyProductScore>,
        writer: WeeklyRankingWriter,
    ): Step = chunkStep(SCORE_PAGING_STEP, reader, writer)

    @Bean(SQL_TASKLET_STEP)
    fun sqlScoreStep(tasklet: SqlWeeklyRankingScoreTasklet): Step =
        StepBuilder(SQL_TASKLET_STEP, jobRepository)
            .tasklet(tasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()

    @Bean
    fun sqlWeeklyRankingScoreTasklet(jdbc: NamedParameterJdbcTemplate) = SqlWeeklyRankingScoreTasklet(jdbc)

    @Bean
    @StepScope
    fun purchaseRowChunkWriter(
        jdbc: JdbcTemplate,
        @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
    ): ItemWriter<PurchaseRow> {
        val snapshotId = WeeklyRankingPeriod.from(parameters).snapshotId
        val processor = PurchaseRowScoreProcessor()
        val writer = PurchaseRowScoreWriter(jdbc)
        return ItemWriter { purchases -> writer.write(processor.process(snapshotId, purchases.items)) }
    }

    @Bean
    @StepScope
    fun productIdChunkWriter(
        jdbc: NamedParameterJdbcTemplate,
        writer: WeeklyRankingWriter,
        @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
    ): ItemWriter<Long> {
        val snapshotId = WeeklyRankingPeriod.from(parameters).snapshotId
        return ItemWriter { productIds ->
            val scores = jdbc.query(
                SCORES_BY_PRODUCT_IDS_SQL,
                mapOf("snapshotId" to snapshotId, "productIds" to productIds.items),
            ) { row, _ -> WeeklyProductScore(snapshotId, row.getLong("product_id"), row.getLong("score")) }
            writer.write(Chunk(scores))
        }
    }

    private fun <T : Any> chunkStep(name: String, reader: ItemStreamReader<T>, writer: ItemWriter<T>): Step =
        StepBuilder(name, jobRepository)
            .chunk<T, T>(CHUNK_SIZE, transactionManager)
            .reader(reader)
            .writer(writer)
            .listener(stepMonitorListener)
            .build()

    companion object {
        const val PROFILE = "ranking-benchmark"
        const val CHUNK_SIZE = 100
        const val PURCHASE_ROW_STEP = "RANKING_BENCHMARK_PURCHASE_ROW_STEP"
        const val PRODUCT_ID_STEP = "RANKING_BENCHMARK_PRODUCT_ID_STEP"
        const val SCORE_CURSOR_STEP = "RANKING_BENCHMARK_SCORE_CURSOR_STEP"
        const val SCORE_PAGING_STEP = "RANKING_BENCHMARK_SCORE_PAGING_STEP"
        const val SQL_TASKLET_STEP = "RANKING_BENCHMARK_SQL_TASKLET_STEP"

        internal const val SCORES_BY_PRODUCT_IDS_SQL =
            "SELECT product_id, SUM(quantity) AS score FROM weekly_ranking_input " +
                "WHERE snapshot_id = :snapshotId AND product_id IN (:productIds) GROUP BY product_id"
    }
}
