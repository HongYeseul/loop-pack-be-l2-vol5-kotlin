package com.loopers.application.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.time.ZonedDateTime

/** 주문 목록의 한 줄, 품목 제외 (읽어온 범위가 달라 OrderInfo 와 분리) */
data class OrderSummaryInfo(
    val id: Long,
    val status: OrderStatus,
    val totalAmount: Long,
    val paidAmount: Long?,
    val createdAt: ZonedDateTime,
) {
    companion object {
        fun from(order: Order): OrderSummaryInfo = OrderSummaryInfo(
            id = order.orderId,
            status = order.status,
            totalAmount = order.totalAmount,
            paidAmount = order.paidAmount,
            createdAt = order.createdAt,
        )
    }
}
