package com.loopers.purchase.interfaces.consumer

import com.loopers.purchase.application.PurchaseActivityService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "commerce.kafka",
    name = ["purchase-activity-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PurchaseActivityListener(
    private val validator: OrderConfirmedValidator,
    private val service: PurchaseActivityService,
) {
    @KafkaListener(
        id = "purchase-activity",
        topics = ["\${commerce.kafka.order-topic:order-events}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "purchaseActivityListenerFactory",
    )
    fun receive(record: ConsumerRecord<String, OrderEventMessage>, acknowledgment: Acknowledgment) {
        validator.validate(record.key(), record.value())?.let(service::record)
        acknowledgment.acknowledge()
    }
}
