package com.loopers.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class CouponClockConfig {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun couponClock(): Clock = Clock.systemUTC()
}
