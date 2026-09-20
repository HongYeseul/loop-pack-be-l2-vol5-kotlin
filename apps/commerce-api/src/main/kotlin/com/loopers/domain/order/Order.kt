package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.time.ZonedDateTime

/**
 * 무엇을 얼마에 사려 한다는 **기록** (기획 4-1절). "살 수 있다" 는 약속이 아니다 — DRAFT 는 재고를
 * 잡지 않는다 (P-23 · D-13).
 *
 * 애그리게잇 루트다 (설계 2-3절). [OrderItem] 은 여기를 통해서만 만들어지고 읽힌다.
 *
 * 논리 삭제 대상이 아니라 [BaseEntity] 를 상속한다 (D-2 · DS-10) — 취소와 만료는 삭제가 아니라 **상태**다.
 */
@Entity
@Table(
    name = "orders",
    indexes = [
        // 내 주문 목록 — 조건과 정렬이 둘 다 여기 있다 (C-11 · P-46 · 설계 7-2절)
        Index(name = "idx_orders_user_latest", columnList = "user_id, id"),
        // 만료 배치 (DS-4)
        Index(name = "idx_orders_status_expires", columnList = "status, expires_at"),
    ],
)
class Order(
    userId: Long,
    items: List<OrderItem>,
    now: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false)
    val userId: Long = userId

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: OrderStatus = OrderStatus.DRAFT
        protected set

    /** 품목의 함수다 (P-28). 밖에서 받지 않는다 — 받으면 합계가 조작될 수 있다. */
    @Column(name = "total_amount", nullable = false)
    val totalAmount: Long = items.sumOf { it.amount }

    /**
     * 확정 전에는 **`null` 이다**. 0 으로 두면 0원 확정(P-30)과 "아직 결제하지 않음" 이 같은 값이 된다.
     */
    @Column(name = "paid_amount")
    var paidAmount: Long? = null
        protected set

    /**
     * 만료 시각을 **계산하지 않고 저장한다** (DS-4). 10분을 나중에 바꿔도 이미 만들어진 주문의
     * 만료 시각은 그대로여서, 고객에게 약속한 시간이 소급해서 달라지지 않는다.
     */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: ZonedDateTime = now.plus(LIFETIME)

    @Column(name = "confirmed_at")
    var confirmedAt: ZonedDateTime? = null
        protected set

    @Column(name = "canceled_at")
    var canceledAt: ZonedDateTime? = null
        protected set

    /** 같은 애그리게잇 안이라 객체로 든다 (설계 2-2절). 밖으로는 읽기 전용으로만 내보낸다 (P-28). */
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private val orderItems: MutableList<OrderItem> = items.toMutableList()

    init {
        if (items.isEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, "주문에는 품목이 하나 이상 있어야 합니다.")
        }
        if (items.distinctBy { it.productId }.size != items.size) {
            throw CoreException(
                ErrorType.DUPLICATE_ORDER_ITEM,
                "[productIds = ${items.map { it.productId }}] 같은 상품이 두 품목으로 들어왔습니다.",
            )
        }
    }

    /** 건네줄 때 쓰는 이름. `BaseEntity.id` 와 같은 값이다 (DS-13). */
    val orderId: Long get() = id

    val items: List<OrderItem> get() = orderItems.toList()

    fun productIds(): List<Long> = orderItems.map { it.productId }

    fun quantityOf(productId: Long): Int = orderItems.first { it.productId == productId }.quantity

    /**
     * 만료 시각을 지났나 (P-32). **정각은 아직 아니다.**
     *
     * `now` 를 받는다 (설계 3절) — 도메인이 `Clock` 을 주입받으면 테스트가 "10분 1초 뒤" 를
     * 값으로 줄 수 없다.
     *
     * **묻기만 한다.** 확정 경로는 만료를 거절만 하고(설계 5절 ⑤), `EXPIRED` 로 바꾸는 것은
     * `commerce-batch` 뿐이다 (DS-4).
     */
    fun isExpired(now: ZonedDateTime): Boolean = now.isAfter(expiresAt)

    /** 확정 (P-26). 결제액은 합계를 옮겨 적는다 — 밖에서 받으면 다른 값이 들어올 수 있다 (P-28). */
    fun confirm(now: ZonedDateTime) {
        guardDraft()
        status = OrderStatus.CONFIRMED
        paidAmount = totalAmount
        confirmedAt = now
    }

    /** 취소 (P-29). 차감한 것이 없어 되돌릴 것도 없다 (D-7). */
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
        /** D-7 · DRAFT 의 수명. "고민하고 결제하는 시간" 으로 잡은 값이다. */
        val LIFETIME: Duration = Duration.ofMinutes(10)

        const val STATUS_MAX_LENGTH = 20
    }
}
