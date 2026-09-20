package com.loopers.domain.point

/**
 * 포인트가 오간 까닭 (P-40 · DS-12).
 *
 * 방향을 부호가 아니라 여기가 든다 — `amount` 는 언제나 오간 **크기**다.
 */
enum class PointTransactionType {
    /** C-7 · 충전 (P-18). */
    CHARGE,

    /** C-10 · 주문 확정 (P-26). 어느 주문 때문인지 `orderId` 가 함께 남는다. */
    USE,
}
