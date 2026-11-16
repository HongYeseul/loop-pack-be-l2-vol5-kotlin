package coupon.stream

import com.loopers.coupon.application.CouponInfo
import com.loopers.coupon.application.CouponIssueResult
import com.loopers.coupon.application.CouponReservedIssuingService
import com.loopers.coupon.application.CouponStreamProcessor
import com.loopers.coupon.domain.CouponBusinessException
import com.loopers.coupon.domain.CouponFailure
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.coupon.stream.domain.CouponStreamMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.transaction.TransactionSystemException
import java.time.Instant

class CouponStreamProcessorTest {
    private val stream = mockk<CouponStream>(relaxed = true)
    private val gate = mockk<CouponExecutionGate>(relaxed = true)
    private val issuer = mockk<CouponReservedIssuingService>()
    private val processor = CouponStreamProcessor(stream, gate, issuer)
    private val message = CouponStreamMessage("1-0", 7, 42)

    private fun prepare() {
        every { gate.status() } returns CouponGateStatus(true, emptySet(), "epoch", null)
        every { stream.readPending(7, "group", "consumer") } returns message
        every { stream.reservation(7, 42) } returns "1-0"
        every { gate.acquire() } returns "permit"
        every { issuer.issue(7, 42) } returns CouponIssueResult(
            CouponInfo(11, 7, "행사", "혜택", 5000, Instant.EPOCH), true,
        )
    }

    @Test
    fun `DB 발급이 끝난 뒤 작업을 ACK하고 실행 자리를 반환한다`() {
        prepare()

        assertThat(processor.processOne(7, "group", "consumer", 1000)).isTrue()

        verifyOrder {
            issuer.issue(7, 42)
            stream.acknowledge(7, "group", "1-0")
            gate.release("permit")
        }
    }

    @Test
    fun `자리가 없으면 작업을 ACK하지 않고 발급도 시작하지 않는다`() {
        prepare()
        every { gate.acquire() } returns null

        assertThat(processor.processOne(7, "group", "consumer", 1000)).isFalse()

        verify(exactly = 0) { issuer.issue(any(), any()) }
        verify(exactly = 0) { stream.acknowledge(any(), any(), any()) }
    }

    @Test
    fun `예약과 연결되지 않은 Stream 작업은 발급하지 않고 닫는다`() {
        prepare()
        every { stream.reservation(7, 42) } returns null

        val error = assertThrows<CouponStreamException> { processor.processOne(7, "group", "consumer", 1000) }

        assertThat(error.failure).isEqualTo(CouponStreamFailure.STATE_LOST)
        verify(exactly = 1) { gate.close() }
        verify(exactly = 0) { issuer.issue(any(), any()) }
        verify(exactly = 0) { stream.acknowledge(any(), any(), any()) }
    }

    @Test
    fun `교착 롤백은 미확인 작업을 남기고 실행 자리만 반환한다`() {
        prepare()
        every { issuer.issue(7, 42) } throws PessimisticLockingFailureException("교착 롤백")

        assertThrows<PessimisticLockingFailureException> { processor.processOne(7, "group", "consumer", 1000) }

        verify(exactly = 0) { stream.acknowledge(any(), any(), any()) }
        verify(exactly = 1) { gate.release("permit") }
        verify(exactly = 0) { gate.retainUncertain(any()) }
    }

    @Test
    fun `DB 종료 불명은 작업과 실행 토큰을 보존한다`() {
        prepare()
        every { issuer.issue(7, 42) } throws TransactionSystemException("커밋 결과 불명")

        assertThrows<TransactionSystemException> { processor.processOne(7, "group", "consumer", 1000) }

        verify(exactly = 1) { gate.retainUncertain("permit") }
        verify(exactly = 0) { gate.release(any()) }
        verify(exactly = 0) { stream.acknowledge(any(), any(), any()) }
    }

    @Test
    fun `발급 후 ACK 실패는 예약을 취소하지 않고 수용을 닫는다`() {
        prepare()
        every { stream.acknowledge(7, "group", "1-0") } throws CouponStreamException(CouponStreamFailure.UNAVAILABLE)

        assertThrows<CouponStreamException> { processor.processOne(7, "group", "consumer", 1000) }

        verify(exactly = 1) { issuer.issue(7, 42) }
        verify(exactly = 1) { gate.close() }
        verify(exactly = 1) { gate.release("permit") }
    }

    @Test
    fun `예약된 작업의 업무 오류도 ACK로 버리지 않는다`() {
        prepare()
        every { issuer.issue(7, 42) } throws CouponBusinessException(CouponFailure.CAMPAIGN_NOT_FOUND)

        assertThrows<CouponBusinessException> { processor.processOne(7, "group", "consumer", 1000) }

        verify(exactly = 0) { stream.acknowledge(any(), any(), any()) }
        verify(exactly = 1) { gate.release("permit") }
    }
}
