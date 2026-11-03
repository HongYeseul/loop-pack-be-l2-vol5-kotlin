package com.loopers.purchase.domain

import java.time.Instant

data class ConfirmedPurchase(
    val orderId: Long,
    val confirmedAt: Instant,
    val items: List<Item>,
) {
    val eventId: String get() = "order-confirmed:v1:$orderId"

    init {
        if (orderId <= 0 || confirmedAt.nano % 1_000 != 0 || items.isEmpty() ||
            items.any { it.productId <= 0 || it.quantity <= 0 } ||
            items.map { it.productId }.distinct().size != items.size
        ) {
            throw InvalidPurchaseEventException("주문 확정 이벤트의 값이 유효하지 않습니다.")
        }
    }

    fun matches(activities: List<PurchaseActivity>): Boolean =
        activities.size == items.size && activities.all { activity ->
            activity.orderId == orderId && activity.confirmedAt == confirmedAt &&
                items.any { it.productId == activity.productId && it.quantity == activity.quantity }
        }

    data class Item(val productId: Long, val quantity: Int)
}

class InvalidPurchaseEventException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class PurchaseContentConflictException(orderId: Long) : RuntimeException("저장된 구매 활동과 내용이 다릅니다: orderId=$orderId")
