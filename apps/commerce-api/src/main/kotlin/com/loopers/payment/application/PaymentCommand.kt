package com.loopers.payment.application

/** 금액은 받지 않음, 저장된 주문 합계 사용 */
class PaymentCommand(
    val attemptKey: String,
    val cardType: String,
    val cardNo: String,
) {
    override fun toString(): String = "PaymentCommand(attemptKey=$attemptKey, cardType=$cardType)"
}
