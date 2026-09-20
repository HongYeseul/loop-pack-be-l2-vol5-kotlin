package com.loopers.application.user

import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminPermission
import com.loopers.domain.admin.AdminUserService
import com.loopers.domain.user.UserService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자의 구매자 조회 (A-14 · A-15 · DS-6).
 *
 * **권한 검사와 조회 기록이 둘 다 여기 있다.** 필터가 아니라 유스케이스에 두는 이유는 같다 —
 * HTTP 가 아닌 입구(배치, 내부 호출)가 생겨도 검사와 기록이 따라와야 한다 (기획 Q-1).
 */
@Component
class UserAdminFacade(
    private val adminUserService: AdminUserService,
    private val userService: UserService,
) {
    /** A-14. 나가는 값은 [UserInfo] 가 이미 가려 놓았다 (P-35). */
    @Transactional(readOnly = true)
    fun getMasked(targetUserId: Long, requester: AdminLoginId): UserInfo {
        adminUserService.requirePermission(requester, AdminPermission.CUSTOMER_READ_MASKED)
        return UserInfo.from(userService.getByIdOrThrow(targetUserId))
    }

    /**
     * A-15. **`purpose` 가 필수 파라미터라 목적 없는 해제 조회를 호출할 수 없다** (DS-6).
     * 문서가 아니라 서명이 막는다.
     */
    @Transactional
    fun getUnmasked(targetUserId: Long, requester: AdminLoginId, purpose: String): UserUnmaskedInfo {
        val actor = adminUserService.requirePermission(requester, AdminPermission.CUSTOMER_READ_UNMASKED)
        val user = userService.getByIdOrThrow(targetUserId)
        adminUserService.recordPersonalDataAccess(actorId = actor.id, targetUserId = user.userId, purpose = purpose)
        return UserUnmaskedInfo.from(user)
    }
}
