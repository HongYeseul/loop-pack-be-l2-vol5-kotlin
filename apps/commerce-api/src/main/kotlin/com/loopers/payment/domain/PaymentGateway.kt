package com.loopers.payment.domain

/** 테스트 PG 계약, 실제 PG 규격이 아님 */
interface PaymentGateway {
    /** 호출자 재시도 없음, 발송 뒤 결과를 모르면 Unknown */
    fun create(request: PaymentRequest): PaymentCreation

    fun find(transactionKey: String): PaymentLookup

    fun findByOrder(providerOrderId: String): PaymentListLookup
}

/** 카드 번호는 저장·로그 금지라 toString 에서 제외 */
class PaymentRequest(
    val providerOrderId: String,
    val cardType: String,
    val cardNo: String,
    val amount: Long,
) {
    override fun toString(): String = "PaymentRequest(providerOrderId=$providerOrderId, cardType=$cardType, amount=$amount)"
}

data class PaymentTransaction(
    val transactionKey: String,
    val providerOrderId: String,
    val amount: Long,
    val status: ExternalPaymentStatus,
)

enum class ExternalPaymentStatus { PENDING, SUCCESS, FAILED }

sealed interface PaymentCreation {
    data class Accepted(val transaction: PaymentTransaction) : PaymentCreation

    /** PG 가 거래를 만들지 않았다고 명시한 거절만 해당 */
    data object RejectedNoEffect : PaymentCreation

    data class Unknown(val reason: String) : PaymentCreation
}

sealed interface PaymentLookup {
    data class Found(val transaction: PaymentTransaction) : PaymentLookup

    data object NotFound : PaymentLookup

    data class Unavailable(val reason: String) : PaymentLookup
}

sealed interface PaymentListLookup {
    data class Listed(val transactions: List<PaymentTransaction>) : PaymentListLookup

    data class Unavailable(val reason: String) : PaymentListLookup
}
