package com.loopers.fixture

import com.loopers.order.domain.Order
import com.loopers.order.domain.OrderItem
import com.loopers.order.domain.OrderLine
import java.time.ZonedDateTime

/**
 * 주문 생성을 한 곳으로 모음 (ProductFixture 와 같은 이유)
 * now 기본값은 BASE_TIME, 만료 경계 테스트는 이 시각에서 "10분 1초 뒤" 를 값으로 생성
 */
object OrderFixture {
    val BASE_TIME: ZonedDateTime = ZonedDateTime.parse("2026-09-21T10:00:00Z")

    const val DEFAULT_QUANTITY = 2
    const val DEFAULT_UNIT_PRICE = 3_500L

    fun line(
        productId: Long,
        quantity: Int = DEFAULT_QUANTITY,
        unitPrice: Long = DEFAULT_UNIT_PRICE,
    ): OrderLine = OrderLine(productId = productId, quantity = quantity, unitPrice = unitPrice)

    fun item(
        orderId: Long,
        productId: Long,
        quantity: Int = DEFAULT_QUANTITY,
        unitPrice: Long = DEFAULT_UNIT_PRICE,
    ): OrderItem = OrderItem(orderId = orderId, line = line(productId, quantity, unitPrice))

    fun order(
        userId: Long,
        lines: List<OrderLine> = listOf(line(productId = 1L)),
        now: ZonedDateTime = BASE_TIME,
    ): Order = Order(userId = userId, lines = lines, now = now)
}
