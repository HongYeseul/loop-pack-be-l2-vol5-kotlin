package com.loopers.payment.domain

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.ZonedDateTime

/**
 * 주문당 한 번의 PG 결제 시도, 카드 정보는 보관하지 않음
 * 내부 시도 키·PG 주문 번호·PG 거래 키를 따로 보존
 */
@Entity
@Table(
    name = "payment_attempt",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_payment_attempt_key", columnNames = ["attempt_key"]),
        UniqueConstraint(name = "uk_payment_attempt_order", columnNames = ["order_id"]),
    ],
    indexes = [
        // callback 의 PG 주문 번호로 시도 찾기
        Index(name = "idx_payment_attempt_provider_order", columnList = "provider_order_id"),
    ],
)
class PaymentAttempt(
    attemptKey: String,
    orderId: Long,
    userId: Long,
    amount: Long,
) : BaseEntity() {
    @Column(name = "attempt_key", nullable = false, length = ATTEMPT_KEY_MAX_LENGTH)
    val attemptKey: String = attemptKey

    @Column(name = "order_id", nullable = false)
    val orderId: Long = orderId

    @Column(name = "user_id", nullable = false)
    val userId: Long = userId

    @Column(name = "provider_order_id", nullable = false, length = 30)
    val providerOrderId: String = providerOrderIdOf(orderId)

    /** 주문 합계 복사, 요청 입력 금지 */
    @Column(name = "amount", nullable = false)
    val amount: Long = amount

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: PaymentStatus = PaymentStatus.REQUESTED
        protected set

    /** 생성 응답을 받기 전에는 null, 유실되면 주문 번호 조회로 채움 */
    @Column(name = "transaction_key", length = 100)
    var transactionKey: String? = null
        protected set

    /** 발송 권한을 얻은 시각, 이후 create 자동 재발송 없음 */
    @Column(name = "dispatched_at")
    var dispatchedAt: ZonedDateTime? = null
        protected set

    @Column(name = "completed_at")
    var completedAt: ZonedDateTime? = null
        protected set

    init {
        if (attemptKey.isBlank() || attemptKey.length > ATTEMPT_KEY_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 1자 이상 ${ATTEMPT_KEY_MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    fun isSameRequest(attemptKey: String, userId: Long, amount: Long): Boolean =
        this.attemptKey == attemptKey && this.userId == userId && this.amount == amount

    /** 이미 아는 거래 키와 다르면 다른 거래의 결과 */
    fun matches(transaction: PaymentTransaction): Boolean =
        transaction.providerOrderId == providerOrderId &&
            transaction.amount == amount &&
            (transactionKey == null || transactionKey == transaction.transactionKey)

    fun accept(transactionKey: String) {
        validateNotFinal()
        bind(transactionKey)
        status = PaymentStatus.PENDING
    }

    fun succeed(transactionKey: String, now: ZonedDateTime) {
        validateNotFinal()
        bind(transactionKey)
        status = PaymentStatus.SUCCESS
        completedAt = now
    }

    /** 생성 전 거절은 거래가 없어 키 없이 종결 */
    fun fail(transactionKey: String?, now: ZonedDateTime) {
        validateNotFinal()
        transactionKey?.let { bind(it) }
        status = PaymentStatus.FAILED
        completedAt = now
    }

    private fun bind(transactionKey: String) {
        if (this.transactionKey != null && this.transactionKey != transactionKey) {
            throw CoreException(ErrorType.PAYMENT_RESULT_MISMATCH, "[orderId = $orderId] 저장된 거래와 다른 거래입니다.")
        }
        this.transactionKey = transactionKey
    }

    private fun validateNotFinal() {
        if (status.isFinal) {
            throw CoreException(ErrorType.CONFLICT, "[orderId = $orderId, status = $status] 이미 종결된 결제 시도입니다.")
        }
    }

    companion object {
        const val ATTEMPT_KEY_MAX_LENGTH = 64
        const val STATUS_MAX_LENGTH = 20

        /** PG 주문 번호는 6자 이상 문자열 계약 */
        fun providerOrderIdOf(orderId: Long): String = "LP-ORD-%010d".format(orderId)
    }
}
