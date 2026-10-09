package com.loopers.application.order

import com.loopers.domain.order.OrderStatus

/** 확정 결과에 잔액 포함, 재조회 사이에 값이 달라질 수 있음 */
data class OrderConfirmInfo(
    val status: OrderStatus,
    val paidAmount: Long,
    val balance: Long,
)
