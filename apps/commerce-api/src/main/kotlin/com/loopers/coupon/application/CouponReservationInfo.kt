package com.loopers.coupon.application

import com.loopers.coupon.domain.CouponRequestStatus

data class CouponReservationInfo(
    val campaignId: Long,
    val status: CouponRequestStatus,
    val couponId: Long? = null,
    val streamId: String? = null,
)

data class CouponReservationSubmission(
    val coupon: CouponInfo? = null,
    val reservation: CouponReservationInfo? = null,
    val accepted: Boolean = false,
)
