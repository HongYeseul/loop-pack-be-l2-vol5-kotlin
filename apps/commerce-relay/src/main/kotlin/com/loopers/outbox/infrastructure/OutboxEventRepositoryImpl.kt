package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxStatus
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class OutboxEventRepositoryImpl(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
) : OutboxEventRepository {
    // 한 문장으로 고르며 잠그면 실행 계획에 따라 고르려고 읽은 행까지 잠금
    override fun claim(
        relayName: String,
        now: ZonedDateTime,
        claimedUntil: ZonedDateTime,
        limit: Int,
        candidateLimit: Int,
    ): List<OutboxEvent> {
        val candidates = outboxEventJpaRepository.findClaimableIds(now, candidateLimit)
        if (candidates.isEmpty()) return emptyList()
        val events = outboxEventJpaRepository.lockClaimable(candidates, now, limit)
        if (events.isNotEmpty()) {
            outboxEventJpaRepository.markClaimed(events.map { it.id }, OutboxStatus.CLAIMED, relayName, claimedUntil, now)
        }
        return events
    }

    override fun markPublished(ids: List<Long>, relayName: String, now: ZonedDateTime): Int =
        if (ids.isEmpty()) {
            0
        } else {
            outboxEventJpaRepository.markPublished(ids, OutboxStatus.CLAIMED, OutboxStatus.PUBLISHED, relayName, now)
        }

    override fun scheduleRetry(
        id: Long,
        relayName: String,
        attemptCount: Int,
        nextAttemptAt: ZonedDateTime,
        error: String,
        now: ZonedDateTime,
    ): Int =
        outboxEventJpaRepository.scheduleRetry(
            id = id,
            claimed = OutboxStatus.CLAIMED,
            pending = OutboxStatus.PENDING,
            relayName = relayName,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            error = error.take(OutboxEvent.LAST_ERROR_MAX_LENGTH),
            now = now,
        )

    override fun markFailed(id: Long, relayName: String, attemptCount: Int, error: String, now: ZonedDateTime): Int =
        outboxEventJpaRepository.markFailed(
            id = id,
            claimed = OutboxStatus.CLAIMED,
            failed = OutboxStatus.FAILED,
            relayName = relayName,
            attemptCount = attemptCount,
            error = error.take(OutboxEvent.LAST_ERROR_MAX_LENGTH),
            now = now,
        )
}
