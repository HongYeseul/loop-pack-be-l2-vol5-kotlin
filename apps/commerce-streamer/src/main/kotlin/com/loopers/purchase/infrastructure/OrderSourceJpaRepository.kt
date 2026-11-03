package com.loopers.purchase.infrastructure

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant

interface OrderSourceJpaRepository : Repository<OrderSourceEntity, Long> {
    @Query(
        """
        select o.confirmedAt from OrderSourceEntity o
        where o.id = :orderId and o.status = 'CONFIRMED' and o.confirmedAt is not null
        """,
    )
    fun findConfirmedAt(@Param("orderId") orderId: Long): Instant?
}

interface OrderItemSourceJpaRepository : Repository<OrderItemSourceEntity, Long> {
    @Query(
        """
        select new com.loopers.purchase.infrastructure.OrderSourceItemQuantity(i.productId, sum(i.quantity))
        from OrderItemSourceEntity i where i.orderId = :orderId
        group by i.productId order by i.productId
        """,
    )
    fun findQuantities(@Param("orderId") orderId: Long): List<OrderSourceItemQuantity>
}

data class OrderSourceItemQuantity(val productId: Long, val quantity: Long)
