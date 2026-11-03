package com.loopers.purchase.application

import com.loopers.purchase.domain.ConfirmedPurchase
import com.loopers.purchase.domain.OrderSourceRepository
import com.loopers.purchase.domain.PurchaseActivityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class PurchaseSourceRecovery(
    private val source: OrderSourceRepository,
    private val activities: PurchaseActivityRepository,
) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun canRebuild(purchase: ConfirmedPurchase): Boolean =
        source.findConfirmed(purchase.orderId) == purchase && activities.findByOrderId(purchase.orderId).isEmpty()
}
