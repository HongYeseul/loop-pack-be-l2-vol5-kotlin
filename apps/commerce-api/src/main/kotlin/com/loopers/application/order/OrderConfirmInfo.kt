package com.loopers.application.order

import com.loopers.domain.order.OrderStatus

/**
 * C-10 · 확정의 결과 (설계 5절).
 *
 * **잔액이 함께 나갑니다.** 확정은 주문과 포인트를 같이 바꾸는 일이라, 결과를 보고 잔액을
 * 다시 조회하게 하면 그 사이에 값이 달라질 수 있습니다.
 */
data class OrderConfirmInfo(
    val status: OrderStatus,
    val paidAmount: Long,
    val balance: Long,
)
