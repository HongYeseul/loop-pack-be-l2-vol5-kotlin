package com.loopers.order.domain

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** PG 결제 시작 때 차감한 재고의 주문별 기록, 수량은 주문 품목 기준 */
@Entity
@Table(
    name = "stock_reservation",
    uniqueConstraints = [UniqueConstraint(name = "uk_stock_reservation_order", columnNames = ["order_id"])],
)
class StockReservation(
    orderId: Long,
) : BaseEntity() {
    @Column(name = "order_id", nullable = false)
    val orderId: Long = orderId

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: StockReservationStatus = StockReservationStatus.RESERVED
        protected set

    fun consume() {
        validateReserved()
        status = StockReservationStatus.CONSUMED
    }

    fun release() {
        validateReserved()
        status = StockReservationStatus.RELEASED
    }

    private fun validateReserved() {
        if (status != StockReservationStatus.RESERVED) {
            throw CoreException(ErrorType.CONFLICT, "[orderId = $orderId, status = $status] 확보 중인 재고가 아닙니다.")
        }
    }

    companion object {
        const val STATUS_MAX_LENGTH = 20
    }
}

enum class StockReservationStatus {
    RESERVED,

    /** PG 성공, 차감 그대로 유지 */
    CONSUMED,

    /** PG 실패, 재고 반환 완료 */
    RELEASED,
}
