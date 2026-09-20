package com.loopers.fixture

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderItem
import java.time.ZonedDateTime

/**
 * 주문을 만드는 자리를 한 곳으로 모은다 — [ProductFixture] 와 같은 이유다.
 *
 * `now` 에 기본값을 두는 것은 만료 경계를 보는 테스트가 **기준 시각을 직접 들어야** 하기 때문이다 (P-32).
 * 그 테스트들은 [BASE_TIME] 에서 출발해 "10분 1초 뒤" 를 값으로 만든다.
 */
object OrderFixture {
    val BASE_TIME: ZonedDateTime = ZonedDateTime.parse("2026-09-21T10:00:00Z")

    const val DEFAULT_QUANTITY = 2
    const val DEFAULT_UNIT_PRICE = 3_500L

    fun item(
        productId: Long,
        quantity: Int = DEFAULT_QUANTITY,
        unitPrice: Long = DEFAULT_UNIT_PRICE,
    ): OrderItem = OrderItem(productId = productId, quantity = quantity, unitPrice = unitPrice)

    fun order(
        userId: Long,
        items: List<OrderItem> = listOf(item(productId = 1L)),
        now: ZonedDateTime = BASE_TIME,
    ): Order = Order(userId = userId, items = items, now = now)
}
