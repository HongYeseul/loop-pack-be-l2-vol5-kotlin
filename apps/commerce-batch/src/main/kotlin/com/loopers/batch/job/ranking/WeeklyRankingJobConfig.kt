package com.loopers.batch.job.ranking

import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishStepConfiguration
import com.loopers.batch.job.ranking.step.score.WeeklyRankingScoreStepConfiguration
import com.loopers.batch.job.ranking.step.snapshot.WeeklyRankingSnapshotStepConfiguration
import com.loopers.batch.job.ranking.step.snapshot.WeeklyRankingSnapshotTasklet.Companion.NO_INPUT_EXIT_CODE
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingJobConfig {
    @Bean(JOB_NAME)
    fun weeklyRankingJob(
        jobRepository: JobRepository,
        @Qualifier(WeeklyRankingSnapshotStepConfiguration.STEP_NAME) snapshotStep: Step,
        @Qualifier(WeeklyRankingScoreStepConfiguration.STEP_NAME) scoreStep: Step,
        @Qualifier(WeeklyRankingPublishStepConfiguration.STEP_NAME) publishStep: Step,
    ): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .start(snapshotStep).on(NO_INPUT_EXIT_CODE).end()
            .from(snapshotStep).on(ExitStatus.COMPLETED.exitCode).to(scoreStep)
            .next(publishStep)
            .from(snapshotStep).on("*").fail()
            .end()
            .build()

    companion object {
        const val JOB_NAME = "WEEKLY_RANKING_JOB"
    }
}
