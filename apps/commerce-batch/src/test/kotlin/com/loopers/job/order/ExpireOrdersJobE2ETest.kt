package com.loopers.job.order

import com.loopers.batch.job.order.ExpireOrdersJobConfig
import com.loopers.domain.order.Order
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.ZonedDateTime

/**
 * 만료된 DRAFT 를 EXPIRED 로 정리한다 (DS-4 · 설계 10절 7단계).
 *
 * **시계를 바꾸지 않는다.** 판단 기준이 `expires_at` 컬럼이라 행을 과거·미래로 심으면 된다.
 */
@SpringBootTest
@SpringBatchTest
@TestPropertySource(properties = ["spring.batch.job.name=${ExpireOrdersJobConfig.JOB_NAME}"])
class ExpireOrdersJobE2ETest @Autowired constructor(
    // [DemoJobE2ETest] 와 같은 이유로 IDE 에서 오류처럼 보일 수 있다.
    private val jobLauncherTestUtils: JobLauncherTestUtils,
    @param:Qualifier(ExpireOrdersJobConfig.JOB_NAME) private val job: Job,
    private val orderJpaRepository: OrderJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("만료 시각이 지난 DRAFT 만 EXPIRED 가 되고, 나머지는 그대로다.")
    @Test
    fun expiresOverdueDraftsOnly() {
        // arrange
        val now = ZonedDateTime.now()
        val overdueDraft = seed(status = "DRAFT", expiresAt = now.minusSeconds(1))
        val liveDraft = seed(status = "DRAFT", expiresAt = now.plusMinutes(10))
        // 확정·취소된 주문도 만료 시각은 지나 있다 — 상태를 함께 보지 않으면 여기가 뒤집힌다 (DS-7)
        val confirmed = seed(status = "CONFIRMED", expiresAt = now.minusMinutes(30))
        val canceled = seed(status = "CANCELED", expiresAt = now.minusMinutes(30))
        jobLauncherTestUtils.job = job

        // act
        val jobExecution = jobLauncherTestUtils.launchJob()

        // assert
        assertAll(
            { assertThat(jobExecution.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode) },
            { assertThat(statusOf(overdueDraft)).isEqualTo("EXPIRED") },
            { assertThat(statusOf(liveDraft)).isEqualTo("DRAFT") },
            { assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED") },
            { assertThat(statusOf(canceled)).isEqualTo("CANCELED") },
        )
    }

    private fun seed(status: String, expiresAt: ZonedDateTime): Long =
        orderJpaRepository.save(Order(status = status, expiresAt = expiresAt)).id

    private fun statusOf(orderId: Long): String =
        orderJpaRepository.findById(orderId).orElseThrow().status
}
