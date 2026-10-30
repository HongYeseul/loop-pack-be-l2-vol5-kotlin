package com.loopers.purchase.infrastructure

import com.loopers.purchase.domain.ConfirmedPurchase
import com.loopers.purchase.domain.OrderSourceRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaOrderSourceRepository(
    private val orders: OrderSourceJpaRepository,
    private val items: OrderItemSourceJpaRepository,
) : OrderSourceRepository {
    @Transactional(readOnly = true)
    override fun findConfirmed(orderId: Long): ConfirmedPurchase? {
        val confirmedAt = orders.findConfirmedAt(orderId) ?: return null
        val quantities = items.findQuantities(orderId).map {
            check(it.quantity in 1..Int.MAX_VALUE.toLong()) { "주문 원본의 구매 수량이 유효하지 않습니다." }
            ConfirmedPurchase.Item(it.productId, it.quantity.toInt())
        }
        return ConfirmedPurchase(orderId, confirmedAt, quantities)
    }
}
