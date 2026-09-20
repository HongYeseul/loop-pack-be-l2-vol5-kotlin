package com.loopers.application.order

import com.loopers.application.user.UserInfo
import com.loopers.domain.support.PageResult

/**
 * A-12 · 한 구매자와 그 사람의 주문들.
 *
 * 구매자가 목록 줄마다 반복되지 않고 위에 한 번 온다 — A-12 는 **구매자를 지정해야** 부를 수 있어서
 * 한 응답의 주문이 모두 같은 사람 것이다 (D-12 1번).
 */
data class UserOrdersInfo(
    val user: UserInfo,
    val orders: PageResult<OrderSummaryInfo>,
)
