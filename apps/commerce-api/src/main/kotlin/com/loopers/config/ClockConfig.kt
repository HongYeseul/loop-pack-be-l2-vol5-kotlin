package com.loopers.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 현재 시각 출처, domain 은 Clock 에 의존하지 않고 now 는 application 이 생성 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
