package com.loopers.order.application

import com.loopers.order.domain.event.OrderConfirmed
import com.loopers.order.domain.event.OrderEventType
import com.loopers.outbox.application.OutboxAppender
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 포인트 확정과 PG 확정이 같은 행을 쓰도록 한 곳에서 변환 */
@Component
class OrderConfirmedOutbox(
    private val outboxAppender: OutboxAppender,
    @Value("\${commerce.kafka.order-topic:${OrderEventType.TOPIC}}")
    private val topic: String,
) {
    fun append(event: OrderConfirmed) {
        outboxAppender.append(
            eventId = event.eventId,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = event.orderId.toString(),
            eventType = event.eventType.eventName,
            schemaVersion = event.schemaVersion,
            topic = topic,
            payload = event,
        )
    }

    companion object {
        const val AGGREGATE_TYPE = "ORDER"
    }
}
