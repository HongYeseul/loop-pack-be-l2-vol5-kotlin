package com.loopers.domain.point

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 한 사용자의 **지금 잔액** (P-18 · P-22).
 *
 * `User` 가 아니라 여기가 잔액을 든다 (설계 2-3절) — `User` 는 식별만 맡고 결제 도메인을 모른다.
 *
 * **원장의 파생값이다** (DS-12). 진실의 출처는 [PointTransaction] 이고 이 행은 "지금 얼마"를
 * 들고 있는 스냅샷이자 잠글 행이다. 불변식 `balance == SUM(transactions)` 는 [PointService] 가 지킨다.
 *
 * 삭제 기능이 없어 [BaseEntity] 를 상속한다 — `deletedAt` 이 없으니 `delete()` 도 컴파일되지 않는다 (DS-10).
 */
@Entity
@Table(name = "point")
class Point(
    userId: Long,
    balance: Long = 0L,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long = userId

    /**
     * `balance` 를 받는 것은 **저장된 상태를 복원**하기 위해서다.
     * 늘리고 줄이는 길은 [charge] 뿐이고, 그 길만이 원장을 함께 남긴다 (P-40).
     */
    @Column(name = "balance", nullable = false)
    var balance: Long = balance
        protected set

    init {
        if (balance < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "잔액은 0 이상이어야 합니다.")
        }
    }

    /**
     * 잔액을 늘린다 (P-18 · P-19 · P-20). 거절하면 **아무것도 바꾸지 않는다** (P-21).
     *
     * 두 거절을 다른 식별자로 나눈다 (DS-8) — 1회 한도 초과는 **금액을 줄이면** 되고,
     * 잔액 상한 초과는 금액을 줄여도 안 될 수 있어 **먼저 써야** 한다.
     *
     * 상한을 도메인 규칙으로 두어 합산 범위 확인이 오버플로 계산이 아니라 **비교 한 줄**이다 (D-5).
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

    companion object {
        /** D-5 · 1회 충전액 상한. */
        const val MAX_CHARGE = 1_000_000L

        /** D-5 · 잔액 상한. */
        const val MAX_BALANCE = 1_000_000_000L
    }
}
