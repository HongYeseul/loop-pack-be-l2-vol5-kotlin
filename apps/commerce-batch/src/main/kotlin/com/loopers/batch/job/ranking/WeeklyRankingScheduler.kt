package com.loopers.batch.job.ranking

import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

class WeeklyRankingScheduler(
    private val jobLauncher: JobLauncher,
    private val job: Job,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(WeeklyRankingScheduler::class.java)

    @Scheduled(cron = "\${ranking.scheduler.cron:0 0 0 * * MON}", zone = "UTC")
    fun run() {
        val periodStart = LocalDate.now(clock.withZone(ZoneOffset.UTC))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .minusWeeks(1)
        val parameters = JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addLong("revision", 1L)
            .toJobParameters()
        val execution = jobLauncher.run(job, parameters)
        if (execution.status == BatchStatus.FAILED) {
            logger.error("주간 랭킹 배치 실패: periodStart={}, executionId={}", periodStart, execution.id)
        } else {
            logger.info("주간 랭킹 배치 실행: periodStart={}, status={}", periodStart, execution.status)
        }
    }
}
