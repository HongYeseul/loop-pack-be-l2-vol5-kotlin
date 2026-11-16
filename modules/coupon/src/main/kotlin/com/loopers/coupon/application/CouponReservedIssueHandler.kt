package com.loopers.coupon.application

import com.loopers.coupon.domain.Coupon
import com.loopers.coupon.domain.CouponBusinessException
import com.loopers.coupon.domain.CouponCampaignRepository
import com.loopers.coupon.domain.CouponFailure
import com.loopers.coupon.domain.CouponRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Component
class CouponReservedIssueHandler(
    private val campaigns: CouponCampaignRepository,
    private val coupons: CouponRepository,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun issue(campaignId: Long, userId: Long): CouponIssueResult {
        coupons.findByCampaignIdAndUserId(campaignId, userId)?.let {
            return CouponIssueResult(CouponInfo.from(it), created = false)
        }
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // 예약 확정 뒤 행사를 닫아도 기존 예약자의 몫은 유지
        val changed = campaigns.incrementForReservation(campaignId, ZonedDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
        val campaign = campaigns.findForUpdate(campaignId)
            ?: throw CouponBusinessException(CouponFailure.CAMPAIGN_NOT_FOUND)
        if (!changed) throw CouponBusinessException(CouponFailure.SOLD_OUT)
        val coupon = coupons.save(
            Coupon(
                campaign.id,
                userId,
                campaign.name,
                campaign.benefitDescription,
                campaign.discountAmount,
                issuedAt,
            ),
        )
        return CouponIssueResult(
            coupon = CouponInfo.from(coupon),
            created = true,
            campaignExhausted = campaign.issuedCount >= campaign.totalQuota,
        )
    }
}
