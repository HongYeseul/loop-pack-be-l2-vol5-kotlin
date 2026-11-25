package com.loopers.order.infrastructure

import com.loopers.order.StockReservation
import org.springframework.data.jpa.repository.JpaRepository

interface StockReservationJpaRepository : JpaRepository<StockReservation, Long> {
    fun findByOrderId(orderId: Long): StockReservation?
}
