package com.loopers.coupon.domain

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "coupon_campaign")
class CouponCampaign(
    @Column(nullable = false, length = 100) val name: String,
    @Column(name = "total_quota", nullable = false) val totalQuota: Int,
    @Column(name = "benefit_description", nullable = false, length = 255) val benefitDescription: String,
    @Column(name = "discount_amount", nullable = false) val discountAmount: Long,
    enabled: Boolean = true,
) : BaseEntity() {
    @Column(nullable = false)
    var enabled: Boolean = enabled
        protected set

    @Column(name = "issued_count", nullable = false)
    var issuedCount: Int = 0
        protected set

    @Column(name = "reservation_initialized", nullable = false)
    var reservationInitialized: Boolean = false
        protected set

    fun markReservationInitialized() {
        reservationInitialized = true
    }

    fun deactivate() {
        enabled = false
    }

    override fun guard() {
        if (name.isBlank() || name.length > 100 || benefitDescription.isBlank() || benefitDescription.length > 255) {
            throw CouponBusinessException(CouponFailure.BAD_REQUEST)
        }
        if (totalQuota < 0 || discountAmount < 0 || issuedCount !in 0..totalQuota) {
            throw CouponBusinessException(CouponFailure.BAD_REQUEST)
        }
    }
}
