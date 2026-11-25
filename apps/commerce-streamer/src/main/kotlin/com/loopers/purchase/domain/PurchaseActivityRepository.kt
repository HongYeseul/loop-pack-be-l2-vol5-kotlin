package com.loopers.purchase.domain

import com.loopers.purchase.PurchaseActivity

interface PurchaseActivityRepository {
    fun findByOrderId(orderId: Long): List<PurchaseActivity>

    fun insert(purchase: ConfirmedPurchase)
}

interface OrderSourceRepository {
    fun findConfirmed(orderId: Long): ConfirmedPurchase?
}
