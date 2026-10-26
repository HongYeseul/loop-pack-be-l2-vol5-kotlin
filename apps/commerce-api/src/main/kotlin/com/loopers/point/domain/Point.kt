package com.loopers.point.domain

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** 사용자별 현재 잔액, 원장(PointTransaction)의 파생값이자 잠금 대상 행 */
@Entity
@Table(name = "point")
class Point(
    userId: Long,
    balance: Long = 0L,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long = userId

    /** 잔액을 받는 것은 저장 상태 복원용, 증감은 charge·use 로만 */
    @Column(name = "balance", nullable = false)
    var balance: Long = balance
        protected set

    init {
        if (balance < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "잔액은 0 이상이어야 합니다.")
        }
    }

    /**
     * 잔액 증가, 거절 시 변경 없음
     * 1회 한도 초과와 잔액 상한 초과는 해결 방법이 달라 오류 분리
     */
    fun charge(amount: Long) {
        if (amount !in 1..MAX_CHARGE) {
            throw CoreException(ErrorType.CHARGE_AMOUNT_INVALID, "[amount = $amount] 1회 충전액은 1 이상 $MAX_CHARGE 이하여야 합니다.")
        }
        if (amount > MAX_BALANCE - balance) {
            throw CoreException(
                ErrorType.BALANCE_LIMIT_EXCEEDED,
                "[balance = $balance, amount = $amount] 충전 결과가 잔액 상한 $MAX_BALANCE 을 넘습니다.",
            )
        }
        this.balance += amount
    }

    /** 잔액 차감, 0원 허용 (0원 확정도 물건이 나가는 주문) */
    fun use(amount: Long) {
        if (amount < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "[amount = $amount] 사용액은 0 이상이어야 합니다.")
        }
        if (amount > balance) {
            throw CoreException(ErrorType.INSUFFICIENT_BALANCE, "[balance = $balance, amount = $amount] 잔액이 부족합니다.")
        }
        this.balance -= amount
    }

    companion object {
        const val MAX_CHARGE = 1_000_000L
        const val MAX_BALANCE = 1_000_000_000L
    }
}
