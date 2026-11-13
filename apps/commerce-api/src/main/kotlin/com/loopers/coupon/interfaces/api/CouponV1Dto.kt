package com.loopers.coupon.interfaces.api

import com.loopers.coupon.application.CouponInfo
import com.loopers.coupon.application.CouponReservationInfo
import com.loopers.coupon.domain.CouponRequestStatus
import java.time.Instant

class CouponV1Dto {
    data class ReservationResponse(
        val campaignId: Long,
        val status: CouponRequestStatus,
        val couponId: Long?,
        val streamId: String?,
    ) {
        companion object {
            fun from(
                info: CouponReservationInfo,
            ) = ReservationResponse(info.campaignId, info.status, info.couponId, info.streamId)
        }
    }

    data class CouponResponse(
        val couponId: Long,
        val campaignId: Long,
        val campaignName: String,
        val benefitDescription: String,
        val discountAmount: Long,
        val issuedAt: Instant,
        val expiresAt: Instant,
    ) {
        companion object {
            fun from(info: CouponInfo): CouponResponse = CouponResponse(
                couponId = info.couponId,
                campaignId = info.campaignId,
                campaignName = info.campaignName,
                benefitDescription = info.benefitDescription,
                discountAmount = info.discountAmount,
                issuedAt = info.issuedAt,
                expiresAt = info.expiresAt,
            )
        }
    }
}
