package com.loopers.payment.infrastructure

import com.loopers.payment.domain.PaymentAttempt
import com.loopers.payment.domain.PaymentAttemptRepository
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class PaymentAttemptRepositoryImpl(
    private val paymentAttemptJpaRepository: PaymentAttemptJpaRepository,
) : PaymentAttemptRepository {
    override fun save(attempt: PaymentAttempt): PaymentAttempt = paymentAttemptJpaRepository.save(attempt)

    override fun findByAttemptKey(attemptKey: String): PaymentAttempt? = paymentAttemptJpaRepository.findByAttemptKey(attemptKey)

    override fun findByOrderId(orderId: Long): PaymentAttempt? = paymentAttemptJpaRepository.findByOrderId(orderId)

    override fun findByOrderIdForUpdate(orderId: Long): PaymentAttempt? =
        paymentAttemptJpaRepository.findForUpdateByOrderId(orderId)

    override fun findByProviderOrderId(providerOrderId: String): PaymentAttempt? =
        paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId)

    override fun claimDispatch(attemptId: Long, now: ZonedDateTime): Boolean =
        paymentAttemptJpaRepository.claimDispatch(attemptId, now) == 1
}
