package com.loopers.coupon.domain

import java.time.ZonedDateTime

interface CouponCampaignRepository {
    fun find(id: Long): CouponCampaign?
    fun findAll(): List<CouponCampaign>
    fun save(campaign: CouponCampaign): CouponCampaign
    fun findForUpdate(campaignId: Long): CouponCampaign?
    fun incrementForReservation(campaignId: Long, now: ZonedDateTime): Boolean
    fun incrementIfAvailable(campaignId: Long, now: ZonedDateTime): Boolean
}
