package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxStatus
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.ZonedDateTime

interface OutboxEventJpaRepository : JpaRepository<OutboxEvent, Long> {
    @Query(
        """
        select o from OutboxEvent o
        where o.status = :pending
          and o.nextAttemptAt <= :now
          and not exists (
            select 1 from OutboxEvent p
            where p.aggregateType = o.aggregateType
              and p.aggregateId = o.aggregateId
              and p.id < o.id
              and p.status in :unfinished
          )
        order by o.id
        """,
    )
    fun findPublishable(
        pending: OutboxStatus,
        unfinished: Collection<OutboxStatus>,
        now: ZonedDateTime,
        limit: Limit,
    ): List<OutboxEvent>

    @Modifying
    @Query(
        """
        update OutboxEvent o
        set o.status = :published, o.publishedAt = :now, o.updatedAt = :now
        where o.id in :ids and o.status = :pending
        """,
    )
    fun markPublished(ids: List<Long>, pending: OutboxStatus, published: OutboxStatus, now: ZonedDateTime): Int

    @Modifying
    @Query(
        """
        update OutboxEvent o
        set o.attemptCount = :attemptCount, o.nextAttemptAt = :nextAttemptAt, o.lastError = :error, o.updatedAt = :now
        where o.id = :id and o.status = :pending
        """,
    )
    fun scheduleRetry(
        id: Long,
        pending: OutboxStatus,
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
        where o.id = :id and o.status = :pending
        """,
    )
    fun markFailed(
        id: Long,
        pending: OutboxStatus,
        failed: OutboxStatus,
        attemptCount: Int,
        error: String,
        now: ZonedDateTime,
    ): Int
}
