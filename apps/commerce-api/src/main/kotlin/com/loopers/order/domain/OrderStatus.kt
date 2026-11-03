package com.loopers.order.domain

/** CANCELED 와 EXPIRED 는 CS 응대가 달라 분리 */
enum class OrderStatus {
    /** 결제 전, 재고·포인트 차감 없음 */
    DRAFT,

    /** PG 결제 시작, 재고 확보 완료·결과 대기 */
    PAYMENT_PENDING,

    /** 재고·포인트 차감 완료, PG 주문은 확보 재고 소비 */
    CONFIRMED,

    /** PG 실패 확인, 확보 재고 반환 */
    PAYMENT_FAILED,

    CANCELED,

    EXPIRED,
    ;

    val isDraft: Boolean get() = this == DRAFT
}
