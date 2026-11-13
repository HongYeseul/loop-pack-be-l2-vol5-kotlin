package com.loopers.coupon.application

import com.loopers.coupon.domain.CouponAlreadyIssuedException
import com.loopers.coupon.domain.CouponBusinessException
import com.loopers.coupon.domain.CouponFailure
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CouponReservedIssuingService(
    private val handler: CouponReservedIssueHandler,
    private val query: CouponQueryService,
) {
    fun issue(campaignId: Long, userId: Long): CouponIssueResult = try {
        handler.issue(campaignId, userId)
    } catch (error: CouponAlreadyIssuedException) {
        existingOrThrow(campaignId, userId, error)
    } catch (error: CouponBusinessException) {
        if (error.failure != CouponFailure.SOLD_OUT) throw error
        existingOrThrow(campaignId, userId, error)
    }

    private fun existingOrThrow(campaignId: Long, userId: Long, error: RuntimeException): CouponIssueResult =
        query.existing(campaignId, userId)?.let { CouponIssueResult(it, created = false) } ?: throw error
}
