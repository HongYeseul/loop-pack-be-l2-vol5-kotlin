package com.loopers.point.infrastructure

import com.loopers.point.domain.PointTransaction
import com.loopers.point.domain.PointTransactionRepository
import org.springframework.stereotype.Component

@Component
class PointTransactionRepositoryImpl(
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
) : PointTransactionRepository {
    override fun save(transaction: PointTransaction): PointTransaction = pointTransactionJpaRepository.save(transaction)
}
