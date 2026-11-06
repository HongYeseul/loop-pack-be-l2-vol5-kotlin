package com.loopers.outbox.domain

import java.time.Duration
import java.time.ZonedDateTime

class OutboxRetryPolicy(
    private val maxAttempts: Int,
    private val backoffInitial: Duration,
    private val backoffMax: Duration,
) {
    init {
        require(maxAttempts > 0)
        require(!backoffInitial.isNegative && backoffInitial <= backoffMax)
    }

    fun isExhausted(attemptCount: Int): Boolean = attemptCount >= maxAttempts

    /** 1회차 실패는 backoffInitial, 이후 두 배씩, backoffMax 상한 */
    fun nextAttemptAt(attemptCount: Int, now: ZonedDateTime): ZonedDateTime {
        require(attemptCount > 0)
        val shift = (attemptCount - 1).coerceAtMost(MAX_SHIFT)
        val delay = backoffInitial.multipliedBy(1L shl shift)
        return now.plus(if (delay > backoffMax) backoffMax else delay)
    }

    companion object {
        // 두 배씩 늘려도 Duration 이 넘치지 않는 범위
        private const val MAX_SHIFT = 30
    }
}
