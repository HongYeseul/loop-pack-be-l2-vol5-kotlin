package com.loopers.order.domain

import com.loopers.order.StockReservation

interface StockReservationRepository {
    fun save(reservation: StockReservation): StockReservation

    fun findByOrderId(orderId: Long): StockReservation?
}
