package com.loopers.coupon.application

import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component

@Component
class CouponAdmissionOperations(
    private val gate: CouponExecutionGate,
    private val setup: CouponReservationSetup,
) {
    fun status(): CouponGateStatus = mapped { gate.status() }
    fun close(): CouponGateStatus = mapped { gate.close() }

    @Synchronized
    fun recover(confirmed: Boolean, expectedTokens: Set<String> = emptySet()): CouponGateStatus = mapped {
        if (!confirmed) throw CoreException(ErrorType.BAD_REQUEST)
        val state = gate.status()
        if (state.accepting || state.activeTokens != expectedTokens) {
            throw CoreException(ErrorType.COUPON_ADMISSION_RECOVERY_CONFLICT)
        }
        setup.prepare()
        gate.recover(confirmed, expectedTokens)
    }

    private fun <T> mapped(action: () -> T): T = try {
        action()
    } catch (error: CouponStreamException) {
        throw error.asCoreException()
    }
}
