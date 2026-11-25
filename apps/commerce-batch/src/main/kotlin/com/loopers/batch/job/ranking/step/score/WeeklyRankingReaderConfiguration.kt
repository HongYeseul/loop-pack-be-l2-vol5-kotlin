package com.loopers.batch.job.ranking.step.score

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingReaderConfiguration {
    @Bean(READER_NAME)
    @StepScope
    fun weeklyProductReader(
        dataSource: DataSource,
        @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
    ): JdbcCursorItemReader<Long> {
        val snapshotId = WeeklyRankingPeriod.from(parameters).snapshotId
        return JdbcCursorItemReaderBuilder<Long>()
            .name(READER_NAME)
            .dataSource(dataSource)
            .sql("SELECT DISTINCT product_id FROM weekly_ranking_input WHERE snapshot_id = ? ORDER BY product_id")
            .queryArguments(snapshotId)
            .rowMapper { result, _ -> result.getLong("product_id") }
            .saveState(true)
            .build()
    }

    companion object {
        const val READER_NAME = "WEEKLY_RANKING_PRODUCT_READER"
    }
}
