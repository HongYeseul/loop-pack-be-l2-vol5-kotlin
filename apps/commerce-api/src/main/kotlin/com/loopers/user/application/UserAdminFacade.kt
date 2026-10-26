package com.loopers.user.application

import com.loopers.admin.domain.AdminLoginId
import com.loopers.admin.domain.AdminPermission
import com.loopers.admin.domain.AdminUserService
import com.loopers.user.domain.UserService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 권한 검사와 조회 기록을 유스케이스에 둠 (HTTP 외 진입점에도 적용) */
@Component
class UserAdminFacade(
    private val adminUserService: AdminUserService,
    private val userService: UserService,
) {
    @Transactional(readOnly = true)
    fun getMasked(targetUserId: Long, requester: AdminLoginId): UserInfo {
        adminUserService.requirePermission(requester, AdminPermission.CUSTOMER_READ_MASKED)
        return UserInfo.from(userService.getByIdOrThrow(targetUserId))
    }

    /** purpose 필수 파라미터로 목적 없는 해제 조회를 시그니처에서 차단 */
    @Transactional
    fun getUnmasked(targetUserId: Long, requester: AdminLoginId, purpose: String): UserUnmaskedInfo {
        val actor = adminUserService.requirePermission(requester, AdminPermission.CUSTOMER_READ_UNMASKED)
        val user = userService.getByIdOrThrow(targetUserId)
        adminUserService.recordPersonalDataAccess(actorId = actor.id, targetUserId = user.userId, purpose = purpose)
        return UserUnmaskedInfo.from(user)
    }
}
