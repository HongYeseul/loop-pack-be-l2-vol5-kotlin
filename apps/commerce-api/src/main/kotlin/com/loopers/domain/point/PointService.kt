package com.loopers.domain.point

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PointService(
    private val pointRepository: PointRepository,
    private val pointTransactionRepository: PointTransactionRepository,
) {
    /**
     * C-7 · 충전. **잔액 변경과 원장 기록을 함께 한다** (DS-12).
     *
     * `Point` 가 혼자 끝내지 못하는 유일한 규칙이라 여기서 조립한다 — 엔티티가 원장 저장소를 알면
     * 도메인이 저장을 하게 된다. 거절되면 한 트랜잭션이라 **둘 다 일어나지 않는다** (P-21).
     *
     * 행이 없으면 만든다. 가입이 없어(기획 3-2절) 사용자가 생길 때 잔액 행을 만들 자리가 없고,
     * 조회가 행을 만들게 하면 **읽기가 쓰기가 된다**.
     */
    @Transactional
    fun charge(userId: Long, amount: Long): Point {
        val point = pointRepository.findByUserId(userId) ?: pointRepository.save(Point(userId = userId))
        point.charge(amount)
        pointTransactionRepository.save(
            PointTransaction(
                userId = userId,
                type = PointTransactionType.CHARGE,
                amount = amount,
                balanceAfter = point.balance,
            ),
        )
        return point
    }

    /**
     * C-10 · 주문 확정의 결제 (P-26). [charge] 와 **같은 모양**이다 — 잔액 변경과 원장 기록을 함께 한다 (DS-12).
     *
     * 0원이어도 원장에 남긴다 (P-30). 남기지 않으면 "포인트를 0원 쓴 주문" 과
     * "원장이 빠진 주문" 이 구분되지 않는다.
     */
    @Transactional
    fun use(userId: Long, amount: Long, orderId: Long): Point {
        val point = pointRepository.findByUserId(userId) ?: pointRepository.save(Point(userId = userId))
        point.use(amount)
        pointTransactionRepository.save(
            PointTransaction(
                userId = userId,
                type = PointTransactionType.USE,
                amount = amount,
                balanceAfter = point.balance,
                orderId = orderId,
            ),
        )
        return point
    }

    /**
     * C-8 · 잔액 조회 (P-22). **행이 없으면 0 이다** — 한 번도 충전하지 않은 것과 0원인 것은
     * 사용자에게 같은 상태이고, 0 은 허용되는 잔액이다 (P-20).
     */
    @Transactional(readOnly = true)
    fun getBalance(userId: Long): Long = pointRepository.findByUserId(userId)?.balance ?: 0L
}
