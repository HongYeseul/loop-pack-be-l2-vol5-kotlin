package com.loopers.outbox.domain

import java.time.ZonedDateTime

interface OutboxEventRepository {

    /** 같은 key 에 앞선 미완료 행이 있으면 제외, id 순 */
    fun findPublishable(now: ZonedDateTime, limit: Int): List<OutboxEvent>

    fun markPublished(ids: List<Long>, now: ZonedDateTime): Int

    fun scheduleRetry(id: Long, attemptCount: Int, nextAttemptAt: ZonedDateTime, error: String, now: ZonedDateTime): Int

    fun markFailed(id: Long, attemptCount: Int, error: String, now: ZonedDateTime): Int
}
