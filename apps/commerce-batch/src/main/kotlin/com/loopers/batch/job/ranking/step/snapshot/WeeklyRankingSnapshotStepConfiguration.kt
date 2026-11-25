package com.loopers.batch.job.ranking.step.snapshot

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.Step
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingSnapshotStepConfiguration {
    @Bean(STEP_NAME)
    fun weeklyRankingSnapshotStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        tasklet: WeeklyRankingSnapshotTasklet,
        stepMonitorListener: StepMonitorListener,
    ): Step =
        StepBuilder(STEP_NAME, jobRepository)
            .tasklet(tasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()

    companion object {
        const val STEP_NAME = "WEEKLY_RANKING_SNAPSHOT_STEP"
    }
}
