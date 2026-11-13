package com.loopers.coupon.domain

interface CouponRepository {
    fun findUserIds(campaignId: Long): List<Long>
    fun save(coupon: Coupon): Coupon
    fun findByCampaignIdAndUserId(campaignId: Long, userId: Long): Coupon?
    fun findOwned(userId: Long): List<Coupon>
}

class CouponAlreadyIssuedException(cause: Throwable) : RuntimeException(cause)
