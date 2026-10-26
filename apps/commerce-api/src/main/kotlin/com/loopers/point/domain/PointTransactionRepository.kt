package com.loopers.point.domain

/** save 만 노출, 원장은 append-only */
interface PointTransactionRepository {
    fun save(transaction: PointTransaction): PointTransaction
}
