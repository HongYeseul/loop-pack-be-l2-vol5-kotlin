package com.loopers.domain.point

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 포인트가 오간 일 한 줄 (P-40 · DS-12). **진실의 출처**이고 [Point.balance] 가 그 파생값이다.
 *
 * **append-only 다.** 고치는 메서드도, 지우는 길도 없다 — 모든 프로퍼티가 `val` 이고
 * [BaseEntity] 를 상속해 `deletedAt` 자체가 없다 (DS-10). 틀린 줄은 고치는 게 아니라
 * 반대 줄을 더해 바로잡는다.
 *
 * [balanceAfter] 를 함께 남기는 이유: 나중에 원장을 읽을 때 앞줄부터 더하지 않아도
 * 그 시점의 잔액을 알 수 있고, `balance == SUM(amount)` 가 깨진 지점을 **한 줄에서** 찾을 수 있다.
 */
@Entity
@Table(
    name = "point_transaction",
    indexes = [
        // 원장 조회·대사 (DS-12 · 설계 7-2절)
        Index(name = "idx_point_transaction_user", columnList = "user_id, id"),
        // 나중에 환불할 때 주문으로 되짚기 (DS-12 · 설계 7-2절)
        Index(name = "idx_point_transaction_order", columnList = "order_id"),
    ],
)
class PointTransaction(
    userId: Long,
    type: PointTransactionType,
    amount: Long,
    balanceAfter: Long,
    orderId: Long? = null,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false)
    val userId: Long = userId

    /** `varchar` 로 고정한다 — `User.status` · `Product.status` 와 같은 이유다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = TYPE_MAX_LENGTH)
    val type: PointTransactionType = type

    /** 오간 **크기**다. 방향은 [type] 이 든다 — 부호로 나타내면 합계와 방향이 한 값에 엉킨다. */
    @Column(name = "amount", nullable = false)
    val amount: Long = amount

    @Column(name = "balance_after", nullable = false)
    val balanceAfter: Long = balanceAfter

    /**
     * 이 줄이 **어느 주문 때문인지** (P-40). 충전 줄에는 없다.
     *
     * 이것이 `orders.paid_amount` 와 잔액을 잇는 값이다 — 환불이 들어올 때
     * "이 주문 때문에 빠진 포인트" 를 되짚는 유일한 길이다 (DS-12).
     */
    @Column(name = "order_id")
    val orderId: Long? = orderId

    companion object {
        const val TYPE_MAX_LENGTH = 20
    }
}
