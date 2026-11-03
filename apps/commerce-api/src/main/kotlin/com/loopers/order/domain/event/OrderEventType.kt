package com.loopers.order.domain.event

import com.fasterxml.jackson.annotation.JsonValue

enum class OrderEventType(@get:JsonValue val eventName: String) {
    PAYMENT_ATTEMPTED("PaymentAttempted"),
    PAYMENT_SUCCEEDED("PaymentSucceeded"),
    ORDER_CONFIRMED("OrderConfirmed"),
    ;

    companion object {
        const val TOPIC = "order-events"
    }
}
