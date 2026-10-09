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

/** 포인트 원장, append-only (잘못된 줄은 반대 줄로 정정) */
@Entity
@Table(
    name = "point_transaction",
    indexes = [
        // 원장 조회·대사
        Index(name = "idx_point_transaction_user", columnList = "user_id, id"),
        // 환불 시 주문 역추적
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

    /** varchar 고정 (User.status 와 동일) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = TYPE_MAX_LENGTH)
    val type: PointTransactionType = type

    /** 오간 크기, 방향은 type 으로 구분 */
    @Column(name = "amount", nullable = false)
    val amount: Long = amount

    @Column(name = "balance_after", nullable = false)
    val balanceAfter: Long = balanceAfter

    /** 원인 주문 (환불 시 역추적용), 충전은 null */
    @Column(name = "order_id")
    val orderId: Long? = orderId

    companion object {
        const val TYPE_MAX_LENGTH = 20
    }
}
