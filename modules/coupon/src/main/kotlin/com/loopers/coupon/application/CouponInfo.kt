package com.loopers.coupon.application

import com.loopers.coupon.domain.Coupon
import java.time.Instant
import java.time.temporal.ChronoUnit

data class CouponInfo(
    val couponId: Long,
    val campaignId: Long,
    val campaignName: String,
    val benefitDescription: String,
    val discountAmount: Long,
    val issuedAt: Instant,
    val expiresAt: Instant = issuedAt.plus(30, ChronoUnit.DAYS),
) {
    companion object {
        fun from(coupon: Coupon): CouponInfo = CouponInfo(
            coupon.id,
            coupon.campaignId,
            coupon.campaignName,
            coupon.benefitDescription,
            coupon.discountAmount,
            coupon.issuedAt,
            coupon.expiresAt,
        )
    }
}

data class CouponIssueResult(val coupon: CouponInfo, val created: Boolean, val campaignExhausted: Boolean = false)
