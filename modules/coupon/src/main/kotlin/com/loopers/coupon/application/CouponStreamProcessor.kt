package com.loopers.coupon.application

import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException

@Component
class CouponStreamProcessor(
    private val stream: CouponStream,
    private val gate: CouponExecutionGate,
    private val issuer: CouponReservedIssuingService,
) {
    fun processOne(campaignId: Long, group: String, consumer: String, claimIdleMs: Long): Boolean {
        if (!gate.status().accepting) return false
        stream.ensureGroup(campaignId, group)
        val message = stream.readPending(campaignId, group, consumer)
            ?: stream.claimPending(campaignId, group, consumer, claimIdleMs)
            ?: stream.read(campaignId, group, consumer, 0)
            ?: return false
        if (stream.reservation(message.campaignId, message.userId) != message.id) {
            gate.close()
            throw CouponStreamException(CouponStreamFailure.STATE_LOST)
        }
        val token = gate.acquire() ?: return false
        var completionKnown = true
        try {
            issuer.issue(message.campaignId, message.userId)
            stream.acknowledge(message.campaignId, group, message.id)
            return true
        } catch (error: RuntimeException) {
            if (error is TransactionException || error is DataAccessResourceFailureException) {
                completionKnown = false
                gate.retainUncertain(token)
            } else if (error is CouponStreamException) {
                gate.close()
            }
            throw error
        } finally {
            if (completionKnown) gate.release(token)
        }
    }
}
