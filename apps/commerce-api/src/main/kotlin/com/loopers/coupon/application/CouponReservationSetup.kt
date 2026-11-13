package com.loopers.coupon.application

import com.loopers.coupon.domain.CouponCampaignRepository
import com.loopers.coupon.domain.CouponRepository
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CouponReservationSetup(
    private val campaigns: CouponCampaignRepository,
    private val coupons: CouponRepository,
    private val stream: CouponStream,
    @Value("\${coupon.consumer.group:coupon-issuers}") private val group: String,
) {
    @Transactional
    fun prepare() {
        campaigns.findAll().forEach { found ->
            val campaign = campaigns.findForUpdate(found.id) ?: throw CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
            if (!campaign.reservationInitialized) {
                stream.initializeCampaign(campaign.id, campaign.totalQuota, coupons.findUserIds(campaign.id))
                campaign.markReservationInitialized()
                campaigns.save(campaign)
            }
            // 재시작 후 키가 없다는 이유로 미발급 예약을 DB 발급 내역만으로 덮어쓰는 복구 방지
            stream.ensureGroup(campaign.id, group)
            stream.status(campaign.id, group)
        }
    }
}
