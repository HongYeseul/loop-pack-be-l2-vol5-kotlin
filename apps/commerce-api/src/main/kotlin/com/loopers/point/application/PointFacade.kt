package com.loopers.point.application

import com.loopers.point.domain.PointService
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.UserService
import org.springframework.stereotype.Component

/** 요청자 확인 후 자기 잔액만 다룸, Point 는 userId 만 알고 요청자를 모름 */
@Component
class PointFacade(
    private val pointService: PointService,
    private val userService: UserService,
) {
    /** 거절 시 잔액 그대로, 원장도 남지 않음 */
    fun charge(loginId: LoginId, amount: Long): PointInfo {
        val user = userService.getActiveOrThrow(loginId)
        return PointInfo(balance = pointService.charge(userId = user.userId, amount = amount).balance)
    }

    /** 한 번도 충전하지 않았으면 0 */
    fun getBalance(loginId: LoginId): PointInfo {
        val user = userService.getActiveOrThrow(loginId)
        return PointInfo(balance = pointService.getBalance(user.userId))
    }
}
