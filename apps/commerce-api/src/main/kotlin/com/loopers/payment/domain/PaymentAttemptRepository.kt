package com.loopers.payment.domain

import java.time.ZonedDateTime

interface PaymentAttemptRepository {
    fun save(attempt: PaymentAttempt): PaymentAttempt

    fun findByAttemptKey(attemptKey: String): PaymentAttempt?

    fun findByOrderId(orderId: Long): PaymentAttempt?

    /** 잠금 읽기, 앞서 commit 된 시도를 트랜잭션 스냅샷과 무관하게 봄 */
    fun findByOrderIdForUpdate(orderId: Long): PaymentAttempt?

    fun findByProviderOrderId(providerOrderId: String): PaymentAttempt?

    /** REQUESTED 인 행만 UNKNOWN 으로 바꿈, 동시 요청 중 한 실행만 true */
    fun claimDispatch(attemptId: Long, now: ZonedDateTime): Boolean
}
