package com.loopers.batch.job.ranking.step.score

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.Step
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingScoreStepConfiguration {
    @Bean(STEP_NAME)
    fun calculateWeeklyScores(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        @Qualifier(WeeklyRankingReaderConfiguration.READER_NAME) weeklyProductReader: JdbcCursorItemReader<Long>,
        weeklyRankingProcessor: ItemProcessor<Long, WeeklyProductScore>,
        weeklyRankingWriter: ItemWriter<WeeklyProductScore>,
        stepMonitorListener: StepMonitorListener,
        weeklyRankingProcessListener: WeeklyRankingProcessListener,
        @Value("\${ranking.chunk-size:100}") chunkSize: Int,
    ): Step =
        StepBuilder(STEP_NAME, jobRepository)
            .chunk<Long, WeeklyProductScore>(chunkSize, transactionManager)
            .reader(weeklyProductReader)
            .processor(weeklyRankingProcessor)
            .writer(weeklyRankingWriter)
            .listener(stepMonitorListener)
            .listener(weeklyRankingProcessListener)
            .faultTolerant()
            .retryLimit(3)
            .retry(TransientDataAccessException::class.java)
            .build()

    companion object {
        const val STEP_NAME = "WEEKLY_RANKING_SCORE_STEP"
    }
}
