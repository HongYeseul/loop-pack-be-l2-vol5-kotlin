package com.loopers.outbox.experiment

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import jakarta.persistence.EntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLTransactionRollbackException

class ExperimentRelays(
    private val entityManager: EntityManager,
    private val publisher: OutboxPublisher,
    private val transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun withoutClaim(limit: Int, afterRead: () -> Unit = {}): Round {
        val events = candidates(limit, lock = false)
        afterRead()
        val results = publisher.publish(events)
        val read = events.map { it.eventId }
        return rollingBackOnDeadlock({ read }) { Round(read, requireNotNull(transaction.execute { markPublished(results) })) }
    }

    fun holdingLock(
        limit: Int,
        isolation: Int = TransactionDefinition.ISOLATION_DEFAULT,
        whileHolding: () -> Unit = {},
    ): Round {
        var read = emptyList<String>()
        return rollingBackOnDeadlock({ read }) {
            requireNotNull(
                TransactionTemplate(transactionManager).apply { isolationLevel = isolation }.execute {
                    val events = candidates(limit, lock = true)
                    read = events.map { it.eventId }
                    whileHolding()
                    val results = publisher.publish(events)
                    Round(read, markPublished(results))
                },
            )
        }
    }

    private fun rollingBackOnDeadlock(read: () -> List<String>, block: () -> Round): Round =
        try {
            block()
        } catch (exception: RuntimeException) {
            if (generateSequence<Throwable>(
                    exception,
                ) { it.cause }.none { it is SQLTransactionRollbackException }
            ) {
                    throw exception
                }
            Round(read(), marked = 0, deadlocks = 1)
        }

    private fun candidates(limit: Int, lock: Boolean): List<OutboxEvent> = entityManager
        .createNativeQuery(if (lock) "$CANDIDATES FOR UPDATE SKIP LOCKED" else CANDIDATES, OutboxEvent::class.java)
        .setParameter("limit", limit)
        .resultList
        .filterIsInstance<OutboxEvent>()

    private fun markPublished(results: List<OutboxPublishResult>): Int {
        val ids = results.filterIsInstance<OutboxPublishResult.Published>().map { it.event.id }
        if (ids.isEmpty()) return 0
        return entityManager.createNativeQuery(
            """
            UPDATE outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
            WHERE id IN (:ids) AND status = 'PENDING'
            """,
        ).setParameter("ids", ids).executeUpdate()
    }

    data class Round(val read: List<String>, val marked: Int, val deadlocks: Int = 0)

    companion object {
        // 실험 행은 모두 시도 시각이 지난 채로 넣으므로 시각 조건 생략
        private const val CANDIDATES = """
            SELECT o.* FROM outbox o
            WHERE o.status = 'PENDING'
              AND NOT EXISTS (
                SELECT 1 FROM outbox p
                WHERE p.aggregate_type = o.aggregate_type
                  AND p.aggregate_id = o.aggregate_id
                  AND p.id < o.id
                  AND p.status IN ('PENDING', 'CLAIMED', 'FAILED')
              )
            ORDER BY o.id
            LIMIT :limit
        """
    }
}
