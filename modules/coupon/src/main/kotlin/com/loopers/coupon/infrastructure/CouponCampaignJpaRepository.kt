package com.loopers.coupon.infrastructure

import com.loopers.coupon.domain.CouponCampaign
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.ZonedDateTime

interface CouponCampaignJpaRepository : JpaRepository<CouponCampaign, Long> {
    fun findFirstByName(name: String): CouponCampaign?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): CouponCampaign?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE CouponCampaign c SET c.issuedCount = c.issuedCount + 1, c.updatedAt = :now " +
            "WHERE c.id = :id AND c.enabled = true AND c.issuedCount < c.totalQuota",
    )
    fun incrementIfAvailable(@Param("id") id: Long, @Param("now") now: ZonedDateTime): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE CouponCampaign c SET c.issuedCount = c.issuedCount + 1, c.updatedAt = :now " +
            "WHERE c.id = :id AND c.issuedCount < c.totalQuota",
    )
    fun incrementForReservation(@Param("id") id: Long, @Param("now") now: ZonedDateTime): Int
}
