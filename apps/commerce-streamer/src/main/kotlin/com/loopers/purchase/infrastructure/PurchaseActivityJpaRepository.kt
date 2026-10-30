package com.loopers.purchase.infrastructure

import com.loopers.purchase.domain.ConfirmedPurchase
import com.loopers.purchase.domain.PurchaseActivity
import com.loopers.purchase.domain.PurchaseActivityRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface PurchaseActivityJpaRepository : JpaRepository<PurchaseActivity, Long> {
    fun findAllByOrderIdOrderByProductIdAsc(orderId: Long): List<PurchaseActivity>
}

@Repository
class PurchaseActivityRepositoryImpl(private val jpa: PurchaseActivityJpaRepository) : PurchaseActivityRepository {
    override fun findByOrderId(orderId: Long): List<PurchaseActivity> = jpa.findAllByOrderIdOrderByProductIdAsc(orderId)

    override fun insert(purchase: ConfirmedPurchase) {
        jpa.saveAllAndFlush(
            purchase.items.map { PurchaseActivity(purchase.orderId, it.productId, it.quantity, purchase.confirmedAt) },
        )
    }
}
