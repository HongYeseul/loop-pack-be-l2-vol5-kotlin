package com.loopers.application.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.time.ZonedDateTime

/**
 * C-11 · 주문 목록의 한 줄. **품목이 없습니다** (설계 6-2절).
 *
 * DS-5 가 나누지 말라고 한 것은 **보는 사람**이고, 이것은 **읽어온 범위**가 다릅니다 —
 * `BrandDetailInfo` 를 나눈 것과 같은 자리입니다.
 */
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
