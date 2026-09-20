package com.loopers.interfaces.api.order

import com.loopers.application.order.OrderCommand
import com.loopers.application.order.OrderConfirmInfo
import com.loopers.application.order.OrderInfo
import com.loopers.application.order.OrderSummaryInfo
import com.loopers.domain.order.OrderStatus
import java.time.ZonedDateTime

class OrderV1Dto {
    /**
     * C-9 · 생성 요청.
     *
     * **단가를 받지 않는다** (P-28). 필드가 없으면 보내도 무시되고, 서버는 상품의 가격만 본다.
     *
     * `quantity` 가 non-null `Int` 인 것이 형식 검사다 (DS-2) — 숫자가 아니면 Jackson 이 거절해
     * `BAD_REQUEST` 가 되고, 0 이하는 `OrderItem` 이 거절해 `INVALID_QUANTITY` 가 된다.
     */
    data class CreateRequest(val items: List<Item>) {
        data class Item(val productId: Long, val quantity: Int)

        fun toCommand(): OrderCommand =
            OrderCommand(items = items.map { OrderCommand.Item(productId = it.productId, quantity = it.quantity) })
    }

    /** 주문 하나 (C-9 · C-11 상세 · C-12). */
    data class OrderResponse(
        val id: Long,
        val status: OrderStatus,
        val totalAmount: Long,
        /**
         * 확정 전에는 `null` 이라 **응답에 키가 없다** (봉투가 null 을 싣지 않는다).
         * 0원 확정(P-30)은 `paidAmount: 0` 으로 나가므로 둘이 구분된다.
         */
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

    /**
     * C-11 · 목록 한 줄. **품목이 없다** (설계 6-2절).
     *
     * 만료 시각도 싣지 않는다 — 남은 시간을 보고 확정할 자리는 상세다.
     */
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

    /** C-10 · 확정 응답 (설계 5절). 바뀐 두 가지 — 주문 상태와 잔액 — 를 함께 돌려준다. */
    data class ConfirmResponse(val status: OrderStatus, val paidAmount: Long, val balance: Long) {
        companion object {
            fun from(info: OrderConfirmInfo): ConfirmResponse =
                ConfirmResponse(status = info.status, paidAmount = info.paidAmount, balance = info.balance)
        }
    }
}
