package com.loopers.batch.job.ranking.benchmark.step.score

import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration.Companion.CHUNK_SIZE
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration.Companion.PROFILE
import com.loopers.batch.job.ranking.step.score.WeeklyProductScore
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.JdbcPagingItemReader
import org.springframework.batch.item.database.Order
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.RowMapper
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@Profile(PROFILE)
class RankingBenchmarkScoreReaderConfiguration {
    @Bean(PURCHASE_READER)
    @StepScope
    fun purchaseReader(
        dataSource: DataSource,
        @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
    ): JdbcPagingItemReader<PurchaseRow> =
        JdbcPagingItemReaderBuilder<PurchaseRow>()
            .name(PURCHASE_READER)
            .dataSource(dataSource)
            .queryProvider(purchasePagingQueryProvider())
            .parameterValues(mapOf("snapshotId" to WeeklyRankingPeriod.from(parameters).snapshotId))
            .pageSize(CHUNK_SIZE)
            .rowMapper { row, _ -> PurchaseRow(row.getLong("product_id"), row.getInt("quantity")) }
            .saveState(true)
            .build()

    @Bean(SCORE_CURSOR_READER)
    @StepScope
    fun scoreCursorReader(
        dataSource: DataSource,
        @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
    ): JdbcCursorItemReader<WeeklyProductScore> {
        val snapshotId = WeeklyRankingPeriod.from(parameters).snapshotId
        return JdbcCursorItemReaderBuilder<WeeklyProductScore>()
            .name(SCORE_CURSOR_READER)
            .dataSource(dataSource)
            .sql("$GROUPED_SCORES_SQL ORDER BY product_id")
            .queryArguments(snapshotId)
            .rowMapper(scoreMapper(snapshotId))
            .saveState(true)
            .build()
    }

    @Bean(SCORE_PAGING_READER)
    @StepScope
    fun scorePagingReader(
        dataSource: DataSource,
        @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
    ): JdbcPagingItemReader<WeeklyProductScore> {
        val snapshotId = WeeklyRankingPeriod.from(parameters).snapshotId
        return JdbcPagingItemReaderBuilder<WeeklyProductScore>()
            .name(SCORE_PAGING_READER)
            .dataSource(dataSource)
            .queryProvider(scorePagingQueryProvider())
            .parameterValues(mapOf("snapshotId" to snapshotId))
            .pageSize(CHUNK_SIZE)
            .rowMapper(scoreMapper(snapshotId))
            .saveState(true)
            .build()
    }

    private fun scoreMapper(snapshotId: String): RowMapper<WeeklyProductScore> =
        RowMapper { row, _ -> WeeklyProductScore(snapshotId, row.getLong("product_id"), row.getLong("score")) }

    companion object {
        const val PURCHASE_READER = "RANKING_BENCHMARK_PURCHASE_READER"
        const val SCORE_CURSOR_READER = "RANKING_BENCHMARK_SCORE_CURSOR_READER"
        const val SCORE_PAGING_READER = "RANKING_BENCHMARK_SCORE_PAGING_READER"

        internal const val GROUPED_SCORES_SQL =
            "SELECT product_id, SUM(quantity) AS score FROM weekly_ranking_input WHERE snapshot_id = ? GROUP BY product_id"

        internal fun purchasePagingQueryProvider() = MySqlPagingQueryProvider().apply {
            setSelectClause("purchase_id, product_id, quantity")
            setFromClause("weekly_ranking_input")
            setWhereClause("snapshot_id = :snapshotId")
            sortKeys = mapOf("purchase_id" to Order.ASCENDING)
        }

        internal fun scorePagingQueryProvider() = MySqlPagingQueryProvider().apply {
            setSelectClause("product_id, SUM(quantity) AS score")
            setFromClause("weekly_ranking_input")
            setWhereClause("snapshot_id = :snapshotId")
            setGroupClause("product_id")
            sortKeys = mapOf("product_id" to Order.ASCENDING)
        }
    }
}
