package com.loopers.application.order

import com.loopers.application.user.UserInfo
import com.loopers.domain.support.PageResult

/** 한 구매자와 그 주문들, 구매자 지정이 필수라 구매자는 한 번만 포함 */
data class UserOrdersInfo(
    val user: UserInfo,
    val orders: PageResult<OrderSummaryInfo>,
)
