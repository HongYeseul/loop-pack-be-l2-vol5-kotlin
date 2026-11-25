package com.loopers.job.ranking

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingScheduler
import com.loopers.batch.job.ranking.WeeklyRankingSchedulingConfiguration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WeeklyRankingSchedulerTest {
    @Test
    fun `월요일 UTC 자정에 직전 주의 시작일로 Job을 실행한다`() {
        val job = mockk<Job>()
        val launcher = mockk<JobLauncher>()
        val parameters = slot<JobParameters>()
        every { launcher.run(job, capture(parameters)) } returns completedExecution()
        val clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneId.of("Asia/Seoul"))

        WeeklyRankingScheduler(launcher, job, clock).run()

        verify(exactly = 1) { launcher.run(job, any()) }
        assertThat(parameters.captured.getLocalDate("periodStart")).isEqualTo(LocalDate.of(2026, 8, 17))
        assertThat(requireNotNull(parameters.captured.getLong("revision"))).isEqualTo(1L)
        assertThat(parameters.captured.identifyingParameters.keys).containsExactlyInAnyOrder("periodStart", "revision")
    }

    @Test
    fun `연도가 바뀌어도 직전 주의 시작일을 UTC 기준으로 계산한다`() {
        val job = mockk<Job>()
        val launcher = mockk<JobLauncher>()
        val parameters = slot<JobParameters>()
        every { launcher.run(job, capture(parameters)) } returns completedExecution()
        val clock = Clock.fixed(Instant.parse("2027-01-04T00:00:00Z"), ZoneId.of("Asia/Seoul"))

        WeeklyRankingScheduler(launcher, job, clock).run()

        assertThat(parameters.captured.getLocalDate("periodStart")).isEqualTo(LocalDate.of(2026, 12, 28))
    }

    @Test
    fun `로컬 날짜가 달라도 UTC 기준으로 완료된 주를 선택한다`() {
        val job = mockk<Job>()
        val launcher = mockk<JobLauncher>()
        val parameters = slot<JobParameters>()
        every { launcher.run(job, capture(parameters)) } returns completedExecution()
        val clock = Clock.fixed(Instant.parse("2026-08-23T23:59:59Z"), ZoneId.of("Asia/Seoul"))

        WeeklyRankingScheduler(launcher, job, clock).run()

        assertThat(parameters.captured.getLocalDate("periodStart")).isEqualTo(LocalDate.of(2026, 8, 10))
    }

    @Test
    fun `같은 주에 다시 실행하면 식별 파라미터가 같다`() {
        val job = mockk<Job>()
        val launcher = mockk<JobLauncher>()
        val parameters = mutableListOf<JobParameters>()
        every { launcher.run(job, capture(parameters)) } returns completedExecution()
        val clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneId.of("UTC"))
        val scheduler = WeeklyRankingScheduler(launcher, job, clock)

        scheduler.run()
        scheduler.run()

        assertThat(parameters).hasSize(2)
        assertThat(parameters[0]).isEqualTo(parameters[1])
    }

    @Test
    fun `scheduler 프로필에서는 등록된 스케줄이 Job을 호출한다`() {
        val latch = CountDownLatch(1)
        val job = mockk<Job>()
        val launcher = mockk<JobLauncher>()
        every { launcher.run(job, any()) } answers {
            latch.countDown()
            completedExecution()
        }

        ApplicationContextRunner()
            .withUserConfiguration(WeeklyRankingSchedulingConfiguration::class.java)
            .withPropertyValues("spring.profiles.active=scheduler", "ranking.scheduler.cron=*/1 * * * * *")
            .withBean(WeeklyRankingJobConfig.JOB_NAME, Job::class.java, { job })
            .withBean(JobLauncher::class.java, { launcher })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
            }
    }

    @Test
    fun `scheduler 프로필이 아니면 스케줄러를 등록하지 않는다`() {
        ApplicationContextRunner()
            .withUserConfiguration(WeeklyRankingSchedulingConfiguration::class.java)
            .run { context -> assertThat(context).doesNotHaveBean(WeeklyRankingScheduler::class.java) }
    }

    companion object {
        private fun completedExecution() = JobExecution(1L).apply { status = BatchStatus.COMPLETED }
    }
}
