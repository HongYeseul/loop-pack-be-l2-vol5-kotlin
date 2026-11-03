package com.loopers.payment.application

import com.loopers.order.domain.Order
import com.loopers.order.domain.OrderStatus
import com.loopers.payment.domain.PaymentAttempt
import com.loopers.payment.domain.PaymentStatus

data class PaymentInfo(
    val orderId: Long,
    val orderStatus: OrderStatus,
    val paymentStatus: PaymentStatus,
    val amount: Long,
    val transactionKey: String?,
) {
    companion object {
        fun from(order: Order, attempt: PaymentAttempt): PaymentInfo = PaymentInfo(
            orderId = order.orderId,
            orderStatus = order.status,
            paymentStatus = attempt.status,
            amount = attempt.amount,
            transactionKey = attempt.transactionKey,
        )
    }
}
