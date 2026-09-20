package com.loopers.application.point

import com.loopers.domain.point.PointService
import com.loopers.domain.user.LoginId
import com.loopers.domain.user.UserService
import org.springframework.stereotype.Component

/**
 * 요청자를 확인하고 자기 잔액만 다루게 하는 자리 (P-02 · 설계 3절).
 *
 * `Point` 는 `userId` 만 들고 요청자가 누구인지 모릅니다. 헤더의 식별자를 사용자로 바꾸는 일은
 * 유스케이스의 전제이지 엔티티의 일이 아닙니다.
 */
@Component
class PointFacade(
    private val pointService: PointService,
    private val userService: UserService,
) {
    /** C-7 · 충전. 거절되면 잔액이 그대로입니다 (P-21) — 한 트랜잭션이라 원장도 남지 않습니다. */
    fun charge(loginId: LoginId, amount: Long): PointInfo {
        val user = userService.getActiveOrThrow(loginId)
        return PointInfo(balance = pointService.charge(userId = user.userId, amount = amount).balance)
    }

    /** C-8 · 잔액 조회 (P-22). 한 번도 충전하지 않았으면 0 입니다 (P-20). */
    fun getBalance(loginId: LoginId): PointInfo {
        val user = userService.getActiveOrThrow(loginId)
        return PointInfo(balance = pointService.getBalance(user.userId))
    }
}
