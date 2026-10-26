package com.loopers.order.interfaces.api.admin

import com.loopers.order.application.OrderInfo
import com.loopers.order.application.OrderSummaryInfo
import com.loopers.order.application.UserOrdersInfo
import com.loopers.order.domain.OrderStatus
import com.loopers.user.interfaces.api.admin.UserAdminV1Dto
import com.loopers.support.interfaces.api.PageResponse
import java.time.ZonedDateTime

class OrderAdminV1Dto {
    /** 한 구매자와 그 주문들, 구매자는 마스킹 상태 */
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

    /** 고객 응답에 없는 userId 포함, 주문에서 구매자 조회로 넘어가는 경로 */
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
