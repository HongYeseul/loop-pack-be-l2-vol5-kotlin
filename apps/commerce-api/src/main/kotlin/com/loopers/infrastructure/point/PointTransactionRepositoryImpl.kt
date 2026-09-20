package com.loopers.infrastructure.point

import com.loopers.domain.point.PointTransaction
import com.loopers.domain.point.PointTransactionRepository
import org.springframework.stereotype.Component

@Component
class PointTransactionRepositoryImpl(
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
) : PointTransactionRepository {
    override fun save(transaction: PointTransaction): PointTransaction = pointTransactionJpaRepository.save(transaction)
}
