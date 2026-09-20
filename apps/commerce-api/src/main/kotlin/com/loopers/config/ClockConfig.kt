package com.loopers.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 현재 시각의 출처.
 *
 * `domain` 은 `Clock` 을 주입받지 않는다. 만료 판단 같은 시각 의존 규칙은
 * `Order.isExpired(now)` 처럼 **`now` 를 파라미터로 받고**, 그 값을 만드는 것은 `application` 이다.
 * 도메인이 스프링 컨테이너를 모르게 두면 테스트가 "10분 1초 뒤" 를 그냥 값으로 줄 수 있다 — 설계 3절.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
