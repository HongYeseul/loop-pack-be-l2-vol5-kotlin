package com.loopers.order.infrastructure

import com.loopers.order.domain.OrderItem
import com.loopers.order.domain.OrderItemRepository
import org.springframework.stereotype.Component

@Component
class OrderItemRepositoryImpl(
    private val orderItemJpaRepository: OrderItemJpaRepository,
) : OrderItemRepository {
    override fun saveAll(items: List<OrderItem>): List<OrderItem> = orderItemJpaRepository.saveAll(items)

    override fun findAllByOrderId(orderId: Long): List<OrderItem> = orderItemJpaRepository.findAllByOrderIdOrderByIdAsc(orderId)
}
