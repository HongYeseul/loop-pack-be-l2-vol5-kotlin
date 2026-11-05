package com.loopers.batch.job.outbox.step

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 주문마다 따로 커밋, 한 주문이 실패해도 다른 주문의 결과는 남음 */
@Component
class OrderConfirmedOutboxBackfill(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
    @Value("\${commerce.kafka.order-topic:order-events}")
    private val topic: String,
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun backfill(orderId: Long): Result =
        try {
            requireNotNull(transaction.execute { backfillInTransaction(orderId) })
        } catch (exception: DuplicateKeyException) {
            // 같은 주문을 동시에 채운 실행이 먼저 커밋, 그 행과 비교
            requireNotNull(transaction.execute { backfillInTransaction(orderId) })
        }

    private fun backfillInTransaction(orderId: Long): Result {
        val order = jdbcTemplate.query("SELECT status, confirmed_at FROM orders WHERE id = ?", { row, _ ->
            // 앱이 UTC 값으로 저장하므로 JVM 시간대를 거치지 않고 읽음
            row.getString("status") to row.getObject("confirmed_at", LocalDateTime::class.java)
        }, orderId).singleOrNull() ?: return Result.Failed(orderId, "주문이 없습니다.")
        val (status, confirmedAt) = order
        if (status != CONFIRMED || confirmedAt == null) return Result.Failed(orderId, "확정된 주문이 아닙니다: status=$status")
        val items = jdbcTemplate.query("SELECT product_id, quantity FROM order_item WHERE order_id = ?", { row, _ ->
            OrderConfirmedPayload.Item(row.getLong("product_id"), row.getInt("quantity"))
        }, orderId)
        if (items.isEmpty()) return Result.Failed(orderId, "주문 품목이 없습니다.")

        val payload = OrderConfirmedPayload.of(orderId, confirmedAt.toInstant(ZoneOffset.UTC), items)
        val body = objectMapper.writeValueAsString(payload)
        val stored = jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_id = ?",
            String::class.java,
            payload.eventId,
        )
            .singleOrNull()
        if (stored != null) {
            return if (objectMapper.readTree(stored) == objectMapper.readTree(body)) {
                Result.AlreadyExists(orderId, payload.eventId)
            } else {
                Result.Failed(orderId, "같은 eventId 의 outbox 본문이 저장된 주문과 다릅니다: eventId=${payload.eventId}")
            }
        }
        insert(payload, body)
        return Result.Created(orderId, payload.eventId)
    }

    // 시각은 앱과 같이 UTC 로 저장해야 relay 가 바로 후보로 봄
    private fun insert(payload: OrderConfirmedPayload, body: String) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox (created_at, updated_at, event_id, aggregate_type, aggregate_id, event_type, schema_version,
              topic, payload, status, attempt_count, next_attempt_at)
            VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, UTC_TIMESTAMP(6))
            """,
            payload.eventId,
            AGGREGATE_TYPE,
            payload.orderId.toString(),
            OrderConfirmedPayload.EVENT_TYPE,
            OrderConfirmedPayload.SCHEMA_VERSION,
            topic,
            body,
        )
    }

    sealed interface Result {
        val orderId: Long

        data class Created(override val orderId: Long, val eventId: String) : Result

        data class AlreadyExists(override val orderId: Long, val eventId: String) : Result

        data class Failed(override val orderId: Long, val reason: String) : Result
    }

    companion object {
        private const val CONFIRMED = "CONFIRMED"
        private const val AGGREGATE_TYPE = "ORDER"
    }
}
