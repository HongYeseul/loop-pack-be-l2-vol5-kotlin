package com.loopers.order.domain

/** CANCELED 와 EXPIRED 는 CS 응대가 달라 분리 */
enum class OrderStatus {
    /** 결제 전, 재고·포인트 차감 없음 */
    DRAFT,

    /** 재고·포인트 차감 완료 */
    CONFIRMED,

    CANCELED,

    EXPIRED,
    ;

    val isDraft: Boolean get() = this == DRAFT
}
