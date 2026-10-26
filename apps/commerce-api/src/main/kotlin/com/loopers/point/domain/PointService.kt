package com.loopers.point.domain

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PointService(
    private val pointRepository: PointRepository,
    private val pointTransactionRepository: PointTransactionRepository,
) {
    /**
     * 충전, 잔액 변경과 원장 기록을 함께 처리
     * 행이 없으면 생성 (조회가 쓰기가 되지 않도록)
     */
    @Transactional
    fun charge(userId: Long, amount: Long): Point {
        val point = pointRepository.findByUserIdForUpdate(userId) ?: pointRepository.save(Point(userId = userId))
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

    /** 0원 사용도 원장에 기록, 원장 누락 주문과 구분 */
    @Transactional
    fun use(userId: Long, amount: Long, orderId: Long): Point {
        val point = pointRepository.findByUserIdForUpdate(userId) ?: pointRepository.save(Point(userId = userId))
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

    /** 행이 없으면 0, 미충전과 0원은 동일 상태 */
    @Transactional(readOnly = true)
    fun getBalance(userId: Long): Long = pointRepository.findByUserId(userId)?.balance ?: 0L
}
