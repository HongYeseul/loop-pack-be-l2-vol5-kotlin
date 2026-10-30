package com.loopers.purchase.application

import com.loopers.purchase.domain.ConfirmedPurchase
import com.loopers.purchase.domain.PurchaseActivity
import com.loopers.purchase.domain.PurchaseActivityRepository
import com.loopers.purchase.domain.PurchaseContentConflictException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Service
class PurchaseActivityService(
    private val repository: PurchaseActivityRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val write = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val read = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }

    fun record(purchase: ConfirmedPurchase) {
        try {
            write.executeWithoutResult {
                val existing = repository.findByOrderId(purchase.orderId)
                if (existing.isEmpty()) repository.insert(purchase) else verify(purchase, existing)
            }
        } catch (error: DataIntegrityViolationException) {
            // 충돌한 INSERT의 rollback 이후, 먼저 commit한 구매 활동으로 판정
            read.executeWithoutResult {
                val existing = repository.findByOrderId(purchase.orderId)
                if (existing.isEmpty()) throw error
                verify(purchase, existing)
            }
        }
    }

    private fun verify(purchase: ConfirmedPurchase, existing: List<PurchaseActivity>) {
        if (!purchase.matches(existing)) throw PurchaseContentConflictException(purchase.orderId)
    }
}
