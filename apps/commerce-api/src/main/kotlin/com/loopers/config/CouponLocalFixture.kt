package com.loopers.config

import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.infrastructure.CouponCampaignJpaRepository
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.User
import com.loopers.user.infrastructure.UserJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile("local")
@ConditionalOnProperty(prefix = "coupon.fixture", name = ["enabled"], havingValue = "true")
class CouponLocalFixture(
    private val campaigns: CouponCampaignJpaRepository,
    private val users: UserJpaRepository,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val campaign = campaigns.findFirstByName("로컬 쿠폰 행사")
            ?: campaigns.saveAndFlush(CouponCampaign("로컬 쿠폰 행사", 100, "5,000원 할인권", 5000))
        val user = users.findByLoginIdValue("couponuser")
            ?: users.saveAndFlush(User(LoginId("couponuser"), "쿠폰 실습 사용자"))
        log.info("로컬 쿠폰 준비 campaignId={} loginId={}", campaign.id, user.loginId.value)
    }
}
