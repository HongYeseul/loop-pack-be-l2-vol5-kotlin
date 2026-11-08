package com.loopers.payment.infrastructure

import com.loopers.payment.domain.PaymentAttempt
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.ZonedDateTime

interface PaymentAttemptJpaRepository : JpaRepository<PaymentAttempt, Long> {
    fun findByAttemptKey(attemptKey: String): PaymentAttempt?

    fun findByOrderId(orderId: Long): PaymentAttempt?

    fun findByProviderOrderId(providerOrderId: String): PaymentAttempt?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByOrderId(orderId: Long): PaymentAttempt?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE PaymentAttempt a
        SET a.status = com.loopers.payment.domain.PaymentStatus.UNKNOWN, a.dispatchedAt = :now, a.updatedAt = :now
        WHERE a.id = :id AND a.status = com.loopers.payment.domain.PaymentStatus.REQUESTED
        """,
    )
    fun claimDispatch(id: Long, now: ZonedDateTime): Int
}
