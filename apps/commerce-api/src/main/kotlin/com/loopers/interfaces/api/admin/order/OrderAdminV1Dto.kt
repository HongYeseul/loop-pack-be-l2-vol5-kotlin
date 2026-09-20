package com.loopers.interfaces.api.admin.order

import com.loopers.application.order.OrderInfo
import com.loopers.application.order.OrderSummaryInfo
import com.loopers.application.order.UserOrdersInfo
import com.loopers.domain.order.OrderStatus
import com.loopers.interfaces.api.admin.user.UserAdminV1Dto
import com.loopers.interfaces.api.support.PageResponse
import java.time.ZonedDateTime

class OrderAdminV1Dto {
    /** A-12 · 한 구매자와 그 사람의 주문들. 구매자는 마스킹된 것이다 (P-35). */
    data class UserOrdersResponse(
        val user: UserAdminV1Dto.UserResponse,
        val orders: PageResponse<OrderSummaryResponse>,
    ) {
        companion object {
            fun from(info: UserOrdersInfo): UserOrdersResponse = UserOrdersResponse(
                user = UserAdminV1Dto.UserResponse.from(info.user),
                orders = PageResponse.from(info.orders, OrderSummaryResponse::from),
            )
        }
    }

    /** 목록 한 줄. 고객 목록과 같은 값이다 — 구매자는 위에 한 번만 온다. */
    data class OrderSummaryResponse(
        val id: Long,
        val status: OrderStatus,
        val totalAmount: Long,
        val paidAmount: Long?,
        val createdAt: ZonedDateTime,
    ) {
        companion object {
            fun from(info: OrderSummaryInfo): OrderSummaryResponse = OrderSummaryResponse(
                id = info.id,
                status = info.status,
                totalAmount = info.totalAmount,
                paidAmount = info.paidAmount,
                createdAt = info.createdAt,
            )
        }
    }

    /**
     * A-13 · 상세. **`userId` 를 싣는다** — 고객 응답에는 없는 값이다 (DS-5).
     * 이 값으로 A-14 를 부를 수 있고, 그게 관리자가 주문에서 사람으로 넘어가는 길이다.
     */
    data class OrderResponse(
        val id: Long,
        val userId: Long,
        val status: OrderStatus,
        val totalAmount: Long,
        val paidAmount: Long?,
        val expiresAt: ZonedDateTime,
        val createdAt: ZonedDateTime,
        val items: List<ItemResponse>,
    ) {
        companion object {
            fun from(info: OrderInfo): OrderResponse = OrderResponse(
                id = info.id,
                userId = info.userId,
                status = info.status,
                totalAmount = info.totalAmount,
                paidAmount = info.paidAmount,
                expiresAt = info.expiresAt,
                createdAt = info.createdAt,
                items = info.items.map { ItemResponse.from(it) },
            )
        }
    }

    data class ItemResponse(val productId: Long, val quantity: Int, val unitPrice: Long) {
        companion object {
            fun from(item: OrderInfo.Item): ItemResponse =
                ItemResponse(productId = item.productId, quantity = item.quantity, unitPrice = item.unitPrice)
        }
    }
}
