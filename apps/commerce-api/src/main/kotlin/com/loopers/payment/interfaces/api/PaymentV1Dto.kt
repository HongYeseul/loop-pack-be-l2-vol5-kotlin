package com.loopers.payment.interfaces.api

import com.loopers.order.domain.OrderStatus
import com.loopers.payment.application.PaymentCommand
import com.loopers.payment.application.PaymentInfo
import com.loopers.payment.domain.PaymentStatus

class PaymentV1Dto {
    /** 카드 값은 합성 값만, 저장·로그 금지라 toString 에서 제외 */
    class StartRequest(
        val paymentAttemptKey: String,
        val cardType: String,
        val cardNo: String,
    ) {
        fun toCommand(): PaymentCommand = PaymentCommand(attemptKey = paymentAttemptKey, cardType = cardType, cardNo = cardNo)

        override fun toString(): String = "StartRequest(paymentAttemptKey=$paymentAttemptKey, cardType=$cardType)"
    }

    /** 상태는 알림일 뿐, 판단은 PG 재조회로 */
    data class CallbackRequest(
        val orderId: String,
        val transactionKey: String,
        val status: String?,
    )

    data class PaymentResponse(
        val orderId: Long,
        val orderStatus: OrderStatus,
        val paymentStatus: PaymentStatus,
        val amount: Long,
        val transactionKey: String?,
    ) {
        companion object {
            fun from(info: PaymentInfo): PaymentResponse = PaymentResponse(
                orderId = info.orderId,
                orderStatus = info.orderStatus,
                paymentStatus = info.paymentStatus,
                amount = info.amount,
                transactionKey = info.transactionKey,
            )
        }
    }
}
