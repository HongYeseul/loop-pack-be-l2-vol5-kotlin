package com.loopers.application.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.time.ZonedDateTime

/**
 * 주문 하나 (C-9 · C-11 · C-12). 품목까지 담습니다.
 *
 * 목록은 이것을 쓰지 않습니다 — [OrderSummaryInfo] 를 씁니다. 응답에 나가지 않는 품목을
 * 목록에서 읽으면 주문 수만큼 조회가 늘고, 담아만 두고 안 쓰면 **비어 있는 품목 목록**이
 * "품목이 없는 주문" 처럼 보입니다.
 */
data class OrderInfo(
    val id: Long,
    /** A-13 만 쓴다. 고객 응답 DTO 는 싣지 않는다 — 나눈 것은 보는 사람이 아니라 응답이다 (DS-5). */
    val userId: Long,
    val status: OrderStatus,
    val totalAmount: Long,
    /** 확정 전에는 `null` 입니다. 0원 확정(P-30)과 구분하기 위해서입니다. */
    val paidAmount: Long?,
    val expiresAt: ZonedDateTime,
    val createdAt: ZonedDateTime,
    val items: List<Item>,
) {
    /** 주문 시점에 **복사한** 단가입니다 (P-28). 상품의 현재 가격이 아닙니다. */
    data class Item(
        val productId: Long,
        val quantity: Int,
        val unitPrice: Long,
    )

    companion object {
        fun from(order: Order): OrderInfo = OrderInfo(
            id = order.orderId,
            userId = order.userId,
            status = order.status,
            totalAmount = order.totalAmount,
            paidAmount = order.paidAmount,
            expiresAt = order.expiresAt,
            createdAt = order.createdAt,
            items = order.items.map { Item(productId = it.productId, quantity = it.quantity, unitPrice = it.unitPrice) },
        )
    }
}
