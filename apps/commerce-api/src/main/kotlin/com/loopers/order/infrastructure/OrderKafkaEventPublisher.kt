package com.loopers.order.infrastructure

import com.loopers.order.domain.event.OrderConfirmed
import com.loopers.order.domain.event.OrderEventType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
@ConditionalOnProperty(prefix = "commerce.kafka", name = ["order-confirmed-enabled"], havingValue = "true", matchIfMissing = true)
class OrderKafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Value("\${commerce.kafka.order-topic:${OrderEventType.TOPIC}}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun publish(event: OrderConfirmed) {
        try {
            kafkaTemplate.send(topic, event.orderId.toString(), event).whenComplete { result, error ->
                if (error != null) {
                    log.error("주문 확정 이벤트 발행 실패: eventId={}, orderId={}, topic={}", event.eventId, event.orderId, topic, error)
                } else {
                    val metadata = result.recordMetadata
                    log.info(
                        "주문 확정 이벤트 발행 완료: eventId={}, topic={}, partition={}, offset={}",
                        event.eventId,
                        metadata.topic(),
                        metadata.partition(),
                        metadata.offset(),
                    )
                }
            }
        } catch (exception: Exception) {
            log.error("주문 확정 이벤트 발행 실패: eventId={}, orderId={}, topic={}", event.eventId, event.orderId, topic, exception)
        }
    }
}
