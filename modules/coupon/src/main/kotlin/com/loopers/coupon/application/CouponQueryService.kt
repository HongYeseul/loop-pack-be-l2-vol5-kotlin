package com.loopers.coupon.application

import com.loopers.coupon.domain.CouponRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(readOnly = true)
class CouponQueryService(private val coupons: CouponRepository) {
    fun existing(campaignId: Long, userId: Long): CouponInfo? =
        coupons.findByCampaignIdAndUserId(campaignId, userId)?.let(CouponInfo::from)

    fun owned(userId: Long): List<CouponInfo> = coupons.findOwned(userId).map(CouponInfo::from)
}
