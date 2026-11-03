package com.loopers.order.domain

interface StockReservationRepository {
    fun save(reservation: StockReservation): StockReservation

    fun findByOrderId(orderId: Long): StockReservation?
}
