package com.loopers.order.interfaces.api

import com.loopers.order.application.OrderCommand
import com.loopers.order.application.OrderConfirmInfo
import com.loopers.order.application.OrderInfo
import com.loopers.order.application.OrderSummaryInfo
import com.loopers.order.domain.OrderStatus
import java.time.ZonedDateTime

class OrderV1Dto {
    /**
     * 단가를 받지 않음 (서버는 상품 가격만 사용)
     * quantity 는 non-null Int 로 형식 검사, 0 이하는 OrderItem 이 INVALID_QUANTITY 로 거절
     */
    data class CreateRequest(val items: List<Item>) {
        data class Item(val productId: Long, val quantity: Int)

        fun toCommand(): OrderCommand =
            OrderCommand(items = items.map { OrderCommand.Item(productId = it.productId, quantity = it.quantity) })
    }

    data class OrderResponse(
        val id: Long,
        val status: OrderStatus,
        val totalAmount: Long,
        /** 확정 전 null 이라 응답에서 키 생략, 0원 확정(paidAmount: 0)과 구분 */
        val paidAmount: Long?,
        val expiresAt: ZonedDateTime,
        val createdAt: ZonedDateTime,
        val items: List<ItemResponse>,
    ) {
        companion object {
            fun from(info: OrderInfo): OrderResponse = OrderResponse(
                id = info.id,
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

    /** 목록 한 줄, 품목·만료 시각 제외 (확정 판단은 상세에서) */
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

    data class ConfirmResponse(val status: OrderStatus, val paidAmount: Long, val balance: Long) {
        companion object {
            fun from(info: OrderConfirmInfo): ConfirmResponse =
                ConfirmResponse(status = info.status, paidAmount = info.paidAmount, balance = info.balance)
        }
    }
}
