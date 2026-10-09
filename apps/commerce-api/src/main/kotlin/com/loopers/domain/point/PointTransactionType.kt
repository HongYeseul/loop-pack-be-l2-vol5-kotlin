package com.loopers.domain.point

/** 방향은 amount 부호가 아니라 이 타입으로 구분 */
enum class PointTransactionType {
    CHARGE,

    /** 주문 확정, orderId 함께 기록 */
    USE,
}
