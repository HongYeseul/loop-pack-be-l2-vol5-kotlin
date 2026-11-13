package com.loopers.coupon.domain

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(
    name = "coupon",
    uniqueConstraints = [UniqueConstraint(name = Coupon.ISSUE_UNIQUE_CONSTRAINT, columnNames = ["campaign_id", "user_id"])],
    indexes = [Index(name = "idx_coupon_user_issued", columnList = "user_id,issued_at,id")],
)
class Coupon(
    @Column(name = "campaign_id", nullable = false) val campaignId: Long,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "campaign_name", nullable = false, length = 100) val campaignName: String,
    @Column(name = "benefit_description", nullable = false, length = 255) val benefitDescription: String,
    @Column(name = "discount_amount", nullable = false) val discountAmount: Long,
    @Column(name = "issued_at", nullable = false) val issuedAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant = issuedAt.plus(30, ChronoUnit.DAYS),
) : BaseEntity() {
    companion object {
        const val ISSUE_UNIQUE_CONSTRAINT = "uk_coupon_campaign_user"
    }
}
