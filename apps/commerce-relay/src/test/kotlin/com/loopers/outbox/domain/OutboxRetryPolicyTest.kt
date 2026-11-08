package com.loopers.outbox.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZonedDateTime

class OutboxRetryPolicyTest {
    private val policy = OutboxRetryPolicy(maxAttempts = 10, backoffInitial = Duration.ofSeconds(1), backoffMax = Duration.ofSeconds(60))
    private val now = ZonedDateTime.parse("2026-11-05T01:00:00Z")

    @DisplayName("다음 시도까지 1초부터 두 배씩 늘고 60초에서 멈춘다.")
    @Test
    fun doublesUntilMax() {
        // act
        val delays = (1..9).map { Duration.between(now, policy.nextAttemptAt(it, now)).seconds }

        // assert
        assertThat(delays).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L)
    }

    @DisplayName("시도 횟수가 아주 커도 상한을 넘지 않는다.")
    @Test
    fun staysAtMaxForLargeAttempts() {
        // act
        val delay = Duration.between(now, policy.nextAttemptAt(1_000, now))

        // assert
        assertThat(delay).isEqualTo(Duration.ofSeconds(60))
    }

    @DisplayName("최대 시도 횟수에 이르면 더 시도하지 않는다.")
    @Test
    fun exhaustsAtMaxAttempts() {
        // assert
        assertThat(policy.isExhausted(9)).isFalse()
        assertThat(policy.isExhausted(10)).isTrue()
    }
}
