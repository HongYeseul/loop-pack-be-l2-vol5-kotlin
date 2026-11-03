package com.loopers.payment.domain

enum class PaymentStatus {
    /** 시도·재고 확보만 저장, 아직 PG 미발송 */
    REQUESTED,

    /** PG 생성 수락, 최종 결과 대기 */
    PENDING,

    /** 발송 시작 뒤 결과 모름, 거래 생성 여부도 모름 */
    UNKNOWN,

    SUCCESS,

    FAILED,
    ;

    val isFinal: Boolean get() = this == SUCCESS || this == FAILED
}
