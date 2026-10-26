package com.loopers.order.application

import com.loopers.order.domain.Order
import com.loopers.order.domain.OrderItem
import com.loopers.order.domain.OrderStatus
import java.time.ZonedDateTime

/** 주문 하나, 품목 포함. 목록은 OrderSummaryInfo 사용 (품목 조회로 주문 수만큼 쿼리 증가) */
data class OrderInfo(
    val id: Long,
    /** 관리자 응답에만 사용 */
    val userId: Long,
    val status: OrderStatus,
    val totalAmount: Long,
    /** 확정 전 null, 0원 확정과 구분 */
    val paidAmount: Long?,
    val expiresAt: ZonedDateTime,
    val createdAt: ZonedDateTime,
    val items: List<Item>,
) {
    /** 주문 시점에 복사한 단가 (상품 현재 가격 아님) */
    data class Item(
        val productId: Long,
        val quantity: Int,
        val unitPrice: Long,
    )

    companion object {
        fun from(order: Order, items: List<OrderItem>): OrderInfo = OrderInfo(
            id = order.orderId,
            userId = order.userId,
            status = order.status,
            totalAmount = order.totalAmount,
            paidAmount = order.paidAmount,
            expiresAt = order.expiresAt,
            createdAt = order.createdAt,
            items = items.map { Item(productId = it.productId, quantity = it.quantity, unitPrice = it.unitPrice) },
        )
    }
}
