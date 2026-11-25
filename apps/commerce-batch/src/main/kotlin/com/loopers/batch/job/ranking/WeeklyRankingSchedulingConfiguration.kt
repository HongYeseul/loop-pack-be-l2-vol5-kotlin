package com.loopers.batch.job.ranking

import org.springframework.batch.core.Job
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@Profile("scheduler")
@EnableScheduling
class WeeklyRankingSchedulingConfiguration {
    @Bean
    fun weeklyRankingScheduler(
        jobLauncher: JobLauncher,
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME) job: Job,
    ): WeeklyRankingScheduler = WeeklyRankingScheduler(jobLauncher, job, Clock.systemUTC())
}
