package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.time.ZonedDateTime

/** 구매 의사 기록, DRAFT 는 재고를 잡지 않음 */
@Entity
@Table(
    name = "orders",
    indexes = [
        // 내 주문 목록 (조건·정렬 모두 포함)
        Index(name = "idx_orders_user_latest", columnList = "user_id, id"),
        // 만료 배치
        Index(name = "idx_orders_status_expires", columnList = "status, expires_at"),
    ],
)
class Order(
    userId: Long,
    lines: List<OrderLine>,
    now: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false)
    val userId: Long = userId

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: OrderStatus = OrderStatus.DRAFT
        protected set

    /** 품목 합계, 외부 입력 금지 (합계 조작 방지) */
    @Column(name = "total_amount", nullable = false)
    val totalAmount: Long = lines.sumOf { it.amount }

    /** 확정 전 null, 0원 확정과 구분 */
    @Column(name = "paid_amount")
    var paidAmount: Long? = null
        protected set

    /** 만료 시각 저장, 만료 기준이 바뀌어도 기존 주문에 소급되지 않음 */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: ZonedDateTime = now.plus(LIFETIME)

    @Column(name = "confirmed_at")
    var confirmedAt: ZonedDateTime? = null
        protected set

    @Column(name = "canceled_at")
    var canceledAt: ZonedDateTime? = null
        protected set

    init {
        if (lines.isEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, "주문에는 품목이 하나 이상 있어야 합니다.")
        }
        if (lines.distinctBy { it.productId }.size != lines.size) {
            throw CoreException(
                ErrorType.DUPLICATE_ORDER_ITEM,
                "[productIds = ${lines.map { it.productId }}] 같은 상품이 두 품목으로 들어왔습니다.",
            )
        }
    }

    /** BaseEntity.id 의 별칭, 호출부에서 무엇의 id 인지 구분 */
    val orderId: Long get() = id

    /** 정각은 만료 아님, now 를 인자로 받아 도메인이 Clock 에 의존하지 않음 */
    fun isExpired(now: ZonedDateTime): Boolean = now.isAfter(expiresAt)

    /** 결제액은 합계에서 복사, 외부 입력 금지 */
    fun confirm(now: ZonedDateTime) {
        guardDraft()
        status = OrderStatus.CONFIRMED
        paidAmount = totalAmount
        confirmedAt = now
    }

    /** 차감한 것이 없어 되돌릴 처리 없음 */
    fun cancel(now: ZonedDateTime) {
        guardDraft()
        status = OrderStatus.CANCELED
        canceledAt = now
    }

    private fun guardDraft() {
        if (!status.isDraft) {
            throw CoreException(ErrorType.ORDER_NOT_DRAFT, "[status = $status] 확정 전(DRAFT) 주문이 아닙니다.")
        }
    }

    companion object {
        /** DRAFT 수명, 고민하고 결제하는 시간 기준 */
        val LIFETIME: Duration = Duration.ofMinutes(10)

        const val STATUS_MAX_LENGTH = 20
    }
}
