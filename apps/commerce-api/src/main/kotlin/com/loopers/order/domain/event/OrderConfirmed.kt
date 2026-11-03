package com.loopers.order.domain.event

import com.fasterxml.jackson.annotation.JsonFormat
import com.loopers.order.domain.Order
import com.loopers.order.domain.OrderItem
import com.loopers.order.domain.OrderStatus
import java.time.Instant

data class OrderConfirmed(
    val orderId: Long,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING)
    val confirmedAt: Instant,
    val items: List<Item>,
) {
    val eventType: OrderEventType = OrderEventType.ORDER_CONFIRMED
    val schemaVersion: Int = 1
    val eventId: String = "order-confirmed:v$schemaVersion:$orderId"

    data class Item(val productId: Long, val quantity: Int)

    companion object {
        fun from(order: Order, items: List<OrderItem>): OrderConfirmed {
            require(order.orderId > 0)
            require(order.status == OrderStatus.CONFIRMED)
            require(items.isNotEmpty() && items.all { it.orderId == order.orderId })
            return OrderConfirmed(
                orderId = order.orderId,
                confirmedAt = requireNotNull(order.confirmedAt).toInstant(),
                items = items.groupBy { it.productId }
                    .map { (productId, lines) -> Item(productId, lines.sumOf { it.quantity }) }
                    .sortedBy { it.productId },
            )
        }
    }
}
