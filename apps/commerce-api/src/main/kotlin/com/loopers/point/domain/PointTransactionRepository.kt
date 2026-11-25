package com.loopers.point.domain

import com.loopers.point.PointTransaction

/** save 만 노출, 원장은 append-only */
interface PointTransactionRepository {
    fun save(transaction: PointTransaction): PointTransaction
}
