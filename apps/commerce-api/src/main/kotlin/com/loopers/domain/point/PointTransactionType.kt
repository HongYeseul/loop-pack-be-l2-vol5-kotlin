package com.loopers.domain.point

/**
 * 포인트가 오간 까닭 (P-40 · DS-12).
 *
 * `USE` 는 6단계 주문 확정에서 더한다 — 지금 만들 수 있는 줄은 충전뿐이고,
 * 먼저 선언해 두면 아무도 만들지 않는 값이 남는다 (설계 10절).
 */
enum class PointTransactionType {
    CHARGE,
}
