package com.loopers.outbox.domain

import java.time.ZonedDateTime

interface OutboxEventRepository {

    /** 앞쪽 후보 candidateLimit 개 안에서 다른 relay 가 잠근 행은 건너뛰고 limit 개까지, 같은 key 에 앞선 미완료 행이 있으면 제외. 트랜잭션 안에서 호출 */
    fun claim(
        relayName: String,
        now: ZonedDateTime,
        claimedUntil: ZonedDateTime,
        limit: Int,
        candidateLimit: Int,
    ): List<OutboxEvent>

    fun markPublished(ids: List<Long>, relayName: String, now: ZonedDateTime): Int

    fun scheduleRetry(
        id: Long,
        relayName: String,
        attemptCount: Int,
        nextAttemptAt: ZonedDateTime,
        error: String,
        now: ZonedDateTime,
    ): Int

    fun markFailed(id: Long, relayName: String, attemptCount: Int, error: String, now: ZonedDateTime): Int
}
