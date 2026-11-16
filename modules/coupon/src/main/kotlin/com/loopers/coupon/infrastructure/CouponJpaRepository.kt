package com.loopers.coupon.infrastructure

import com.loopers.coupon.domain.Coupon
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponJpaRepository : JpaRepository<Coupon, Long> {
    @Query("SELECT c.userId FROM Coupon c WHERE c.campaignId = :campaignId")
    fun findUserIds(@Param("campaignId") campaignId: Long): List<Long>

    fun findByCampaignIdAndUserId(campaignId: Long, userId: Long): Coupon?
    fun findByUserIdOrderByIssuedAtDescIdDesc(userId: Long): List<Coupon>
}
