package com.loopers.coupon.application

import com.loopers.coupon.domain.CouponCampaignRepository
import com.loopers.coupon.domain.CouponRequestStatus
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.UserService
import org.springframework.stereotype.Component

@Component
class CouponFacade(
    private val users: UserService,
    private val query: CouponQueryService,
    private val campaigns: CouponCampaignRepository,
    private val stream: CouponStream,
    private val gate: CouponExecutionGate,
    private val soldOutCache: CouponSoldOutCache,
) {
    fun issue(loginId: LoginId, campaignId: Long): CouponReservationSubmission {
        val user = users.getActiveOrThrow(loginId)
        val knownSoldOut = soldOutCache.contains(campaignId)
        query.existing(campaignId, user.userId)?.let { return CouponReservationSubmission(coupon = it) }
        if (knownSoldOut) throw CoreException(ErrorType.COUPON_SOLD_OUT)
        val campaign = campaigns.find(campaignId) ?: throw CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
        if (campaign.issuedCount >= campaign.totalQuota) {
            soldOutCache.remember(campaignId)
            query.existing(campaignId, user.userId)?.let { return CouponReservationSubmission(coupon = it) }
            throw CoreException(ErrorType.COUPON_SOLD_OUT)
        }
        return streamCall {
            stream.reservation(campaignId, user.userId)?.let {
                return@streamCall CouponReservationSubmission(
                    reservation = CouponReservationInfo(campaignId, CouponRequestStatus.PENDING, streamId = it),
                )
            }
            if (!campaign.enabled) throw CoreException(ErrorType.COUPON_CAMPAIGN_INACTIVE)
            val receipt = stream.reserve(campaignId, user.userId)
            CouponReservationSubmission(
                reservation = CouponReservationInfo(campaignId, CouponRequestStatus.PENDING, streamId = receipt.streamId),
                accepted = receipt.created,
            )
        }
    }

    fun result(loginId: LoginId, campaignId: Long): CouponReservationInfo {
        val user = users.getActiveOrThrow(loginId)
        query.existing(campaignId, user.userId)?.let {
            return CouponReservationInfo(campaignId, CouponRequestStatus.ISSUED, couponId = it.couponId)
        }
        if (campaigns.find(campaignId) == null) throw CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
        val receipt = streamCall { stream.reservation(campaignId, user.userId) }
            ?: throw CoreException(ErrorType.NOT_FOUND)
        return CouponReservationInfo(campaignId, CouponRequestStatus.PENDING, streamId = receipt)
    }

    fun owned(loginId: LoginId): List<CouponInfo> = query.owned(users.getActiveOrThrow(loginId).userId)

    private fun <T> streamCall(action: () -> T): T = try {
        action()
    } catch (error: CouponStreamException) {
        if (error.failure in setOf(CouponStreamFailure.UNAVAILABLE, CouponStreamFailure.STATE_LOST)) {
            runCatching { gate.close() }
        }
        throw error.asCoreException()
    }
}
