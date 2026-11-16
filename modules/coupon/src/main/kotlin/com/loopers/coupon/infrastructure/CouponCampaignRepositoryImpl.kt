package com.loopers.coupon.infrastructure

import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.domain.CouponCampaignRepository
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class CouponCampaignRepositoryImpl(private val campaigns: CouponCampaignJpaRepository) : CouponCampaignRepository {
    override fun find(id: Long) = campaigns.findById(id).orElse(null)
    override fun findAll() = campaigns.findAll()
    override fun save(campaign: CouponCampaign): CouponCampaign = campaigns.saveAndFlush(campaign)
    override fun findForUpdate(campaignId: Long): CouponCampaign? = campaigns.findForUpdateById(campaignId)
    override fun incrementForReservation(campaignId: Long, now: ZonedDateTime): Boolean =
        campaigns.incrementForReservation(campaignId, now) == 1
    override fun incrementIfAvailable(campaignId: Long, now: ZonedDateTime): Boolean =
        campaigns.incrementIfAvailable(campaignId, now) == 1
}
