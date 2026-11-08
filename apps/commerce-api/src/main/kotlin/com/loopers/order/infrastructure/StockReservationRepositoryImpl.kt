package com.loopers.order.infrastructure

import com.loopers.order.domain.StockReservation
import com.loopers.order.domain.StockReservationRepository
import org.springframework.stereotype.Component

@Component
class StockReservationRepositoryImpl(
    private val stockReservationJpaRepository: StockReservationJpaRepository,
) : StockReservationRepository {
    override fun save(reservation: StockReservation): StockReservation = stockReservationJpaRepository.save(reservation)

    override fun findByOrderId(orderId: Long): StockReservation? = stockReservationJpaRepository.findByOrderId(orderId)
}
