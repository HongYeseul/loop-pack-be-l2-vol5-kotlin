package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxStatus
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class OutboxEventRepositoryImpl(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
) : OutboxEventRepository {
    override fun findPublishable(now: ZonedDateTime, limit: Int): List<OutboxEvent> =
        outboxEventJpaRepository.findPublishable(OutboxStatus.PENDING, UNFINISHED, now, Limit.of(limit))

    override fun markPublished(ids: List<Long>, now: ZonedDateTime): Int =
        if (ids.isEmpty()) 0 else outboxEventJpaRepository.markPublished(ids, OutboxStatus.PENDING, OutboxStatus.PUBLISHED, now)

    override fun scheduleRetry(
        id: Long,
        attemptCount: Int,
        nextAttemptAt: ZonedDateTime,
        error: String,
        now: ZonedDateTime,
    ): Int =
        outboxEventJpaRepository.scheduleRetry(
            id = id,
            pending = OutboxStatus.PENDING,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            error = error.take(OutboxEvent.LAST_ERROR_MAX_LENGTH),
            now = now,
        )

    override fun markFailed(id: Long, attemptCount: Int, error: String, now: ZonedDateTime): Int =
        outboxEventJpaRepository.markFailed(
            id = id,
            pending = OutboxStatus.PENDING,
            failed = OutboxStatus.FAILED,
            attemptCount = attemptCount,
            error = error.take(OutboxEvent.LAST_ERROR_MAX_LENGTH),
            now = now,
        )

    companion object {
        private val UNFINISHED = listOf(OutboxStatus.PENDING, OutboxStatus.FAILED)
    }
}
