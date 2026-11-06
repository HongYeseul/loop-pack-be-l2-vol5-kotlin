package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.ZonedDateTime

interface OutboxEventJpaRepository : JpaRepository<OutboxEvent, Long> {
    @Query(
        value = """
        SELECT o.id FROM outbox o
        WHERE ((o.status = 'PENDING' AND o.next_attempt_at <= :now)
            OR (o.status = 'CLAIMED' AND o.claimed_until < :now))
          AND NOT EXISTS (
            SELECT 1 FROM outbox p
            WHERE p.aggregate_type = o.aggregate_type
              AND p.aggregate_id = o.aggregate_id
              AND p.id < o.id
              AND p.status IN ('PENDING', 'CLAIMED', 'FAILED')
          )
        ORDER BY o.id
        LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findClaimableIds(now: ZonedDateTime, limit: Int): List<Long>

    @Query(
        value = """
        SELECT o.* FROM outbox o
        WHERE o.id IN (:ids)
          AND ((o.status = 'PENDING' AND o.next_attempt_at <= :now)
            OR (o.status = 'CLAIMED' AND o.claimed_until < :now))
        ORDER BY o.id
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockClaimable(ids: List<Long>, now: ZonedDateTime, limit: Int): List<OutboxEvent>

    @Modifying
    @Query(
        """
        update OutboxEvent o
        set o.status = :claimed, o.claimedBy = :relayName, o.claimedUntil = :claimedUntil, o.updatedAt = :now
        where o.id in :ids
        """,
    )
    fun markClaimed(
        ids: List<Long>,
        claimed: OutboxStatus,
        relayName: String,
        claimedUntil: ZonedDateTime,
        now: ZonedDateTime,
    ): Int

    @Modifying
    @Query(
        """
        update OutboxEvent o
        set o.status = :published, o.publishedAt = :now, o.updatedAt = :now
        where o.id in :ids and o.status = :claimed and o.claimedBy = :relayName
        """,
    )
    fun markPublished(
        ids: List<Long>,
        claimed: OutboxStatus,
        published: OutboxStatus,
        relayName: String,
        now: ZonedDateTime,
    ): Int

    @Modifying
    @Query(
        """
        update OutboxEvent o
        set o.status = :pending, o.attemptCount = :attemptCount, o.nextAttemptAt = :nextAttemptAt,
            o.lastError = :error, o.updatedAt = :now
        where o.id = :id and o.status = :claimed and o.claimedBy = :relayName
        """,
    )
    fun scheduleRetry(
        id: Long,
        claimed: OutboxStatus,
        pending: OutboxStatus,
        relayName: String,
        attemptCount: Int,
        nextAttemptAt: ZonedDateTime,
        error: String,
        now: ZonedDateTime,
    ): Int

    @Modifying
    @Query(
        """
        update OutboxEvent o
        set o.status = :failed, o.attemptCount = :attemptCount, o.lastError = :error, o.updatedAt = :now
        where o.id = :id and o.status = :claimed and o.claimedBy = :relayName
        """,
    )
    fun markFailed(
        id: Long,
        claimed: OutboxStatus,
        failed: OutboxStatus,
        relayName: String,
        attemptCount: Int,
        error: String,
        now: ZonedDateTime,
    ): Int
}
