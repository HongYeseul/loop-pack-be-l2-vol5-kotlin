package com.loopers.order.infrastructure

import com.loopers.order.domain.OrderItem
import org.springframework.data.jpa.repository.JpaRepository

interface OrderItemJpaRepository : JpaRepository<OrderItem, Long> {
    fun findAllByOrderIdOrderByIdAsc(orderId: Long): List<OrderItem>
}
