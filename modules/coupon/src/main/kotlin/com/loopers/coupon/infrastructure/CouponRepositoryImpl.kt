package com.loopers.coupon.infrastructure

import com.loopers.coupon.domain.Coupon
import com.loopers.coupon.domain.CouponAlreadyIssuedException
import com.loopers.coupon.domain.CouponRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.sql.SQLException

@Component
class CouponRepositoryImpl(private val coupons: CouponJpaRepository) : CouponRepository {
    override fun findUserIds(campaignId: Long) = coupons.findUserIds(campaignId)
    override fun save(coupon: Coupon): Coupon = try {
        coupons.saveAndFlush(coupon)
    } catch (error: DataIntegrityViolationException) {
        val duplicate = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<SQLException>()
            .any { sql ->
                val constraint = DUPLICATE_KEY.find(sql.message.orEmpty())?.groupValues?.get(1)?.substringAfterLast('.')
                sql.errorCode == 1062 && constraint == Coupon.ISSUE_UNIQUE_CONSTRAINT
            }
        if (duplicate) throw CouponAlreadyIssuedException(error)
        throw error
    }

    override fun findByCampaignIdAndUserId(campaignId: Long, userId: Long): Coupon? =
        coupons.findByCampaignIdAndUserId(campaignId, userId)

    override fun findOwned(userId: Long): List<Coupon> = coupons.findByUserIdOrderByIssuedAtDescIdDesc(userId)

    companion object {
        private val DUPLICATE_KEY = Regex("""for key ['`]([^'`]+)['`]""", RegexOption.IGNORE_CASE)
    }
}
