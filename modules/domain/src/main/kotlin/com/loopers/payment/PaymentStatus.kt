package com.loopers.payment

enum class PaymentStatus {
    REQUESTED,
    PENDING,
    UNKNOWN,
    SUCCESS,
    FAILED,
    ;

    val isFinal: Boolean get() = this == SUCCESS || this == FAILED
}
