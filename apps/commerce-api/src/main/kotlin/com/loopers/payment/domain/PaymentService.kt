package com.loopers.payment.domain

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

@Component
class PaymentService(
    private val paymentAttemptRepository: PaymentAttemptRepository,
) {
    @Transactional
    fun create(attemptKey: String, orderId: Long, userId: Long, amount: Long): PaymentAttempt =
        paymentAttemptRepository.save(
            PaymentAttempt(attemptKey = attemptKey, orderId = orderId, userId = userId, amount = amount),
        )

    @Transactional(readOnly = true)
    fun findByAttemptKey(attemptKey: String): PaymentAttempt? = paymentAttemptRepository.findByAttemptKey(attemptKey)

    @Transactional(readOnly = true)
    fun findByOrderId(orderId: Long): PaymentAttempt? = paymentAttemptRepository.findByOrderId(orderId)

    /** 주문 잠금을 기다린 뒤에도 먼저 생긴 시도를 놓치지 않도록 잠금 읽기 */
    @Transactional
    fun findByOrderIdForUpdate(orderId: Long): PaymentAttempt? = paymentAttemptRepository.findByOrderIdForUpdate(orderId)

    @Transactional
    fun getByOrderIdForUpdateOrThrow(orderId: Long): PaymentAttempt =
        paymentAttemptRepository.findByOrderIdForUpdate(orderId)
            ?: throw CoreException(ErrorType.PAYMENT_NOT_FOUND, "[orderId = $orderId] 결제 시도를 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getByOrderIdOrThrow(orderId: Long): PaymentAttempt =
        paymentAttemptRepository.findByOrderId(orderId)
            ?: throw CoreException(ErrorType.PAYMENT_NOT_FOUND, "[orderId = $orderId] 결제 시도를 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getByProviderOrderIdOrThrow(providerOrderId: String): PaymentAttempt =
        paymentAttemptRepository.findByProviderOrderId(providerOrderId)
            ?: throw CoreException(ErrorType.PAYMENT_NOT_FOUND, "[providerOrderId = $providerOrderId] 결제 시도를 찾을 수 없습니다.")

    @Transactional
    fun claimDispatch(attemptId: Long, now: ZonedDateTime): Boolean = paymentAttemptRepository.claimDispatch(attemptId, now)
}
