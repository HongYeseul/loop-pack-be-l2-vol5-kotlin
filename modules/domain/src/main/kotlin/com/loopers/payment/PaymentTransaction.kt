package com.loopers.payment

data class PaymentTransaction(
    val transactionKey: String,
    val providerOrderId: String,
    val amount: Long,
    val status: ExternalPaymentStatus,
)

enum class ExternalPaymentStatus { PENDING, SUCCESS, FAILED }
