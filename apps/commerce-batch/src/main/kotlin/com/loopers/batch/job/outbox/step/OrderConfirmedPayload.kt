package com.loopers.batch.job.outbox.step

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant

// commerce-api 의 OrderConfirmed 계약과 함께 수정, 필드 순서까지 같아야 저장된 본문과 비교됨
data class OrderConfirmedPayload(
    val orderId: Long,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING)
    val confirmedAt: Instant,
    val items: List<Item>,
) {
    val eventType: String = EVENT_TYPE
    val schemaVersion: Int = SCHEMA_VERSION
    val eventId: String = "order-confirmed:v$SCHEMA_VERSION:$orderId"

    data class Item(val productId: Long, val quantity: Int)

    companion object {
        const val EVENT_TYPE = "OrderConfirmed"
        const val SCHEMA_VERSION = 1

        fun of(orderId: Long, confirmedAt: Instant, lines: List<Item>): OrderConfirmedPayload = OrderConfirmedPayload(
            orderId = orderId,
            confirmedAt = confirmedAt,
            items = lines.groupBy { it.productId }
                .map { (productId, sameProduct) -> Item(productId, sameProduct.sumOf { it.quantity }) }
                .sortedBy { it.productId },
        )
    }
}
