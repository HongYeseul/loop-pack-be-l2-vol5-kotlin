package com.loopers.domain.admin

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AdminUserService(
    private val adminUserRepository: AdminUserRepository,
    private val adminRoleHistoryRepository: AdminRoleHistoryRepository,
    private val personalDataAccessLogRepository: PersonalDataAccessLogRepository,
) {
    @Transactional(readOnly = true)
    fun getByLoginIdOrThrow(loginId: AdminLoginId): AdminUser =
        adminUserRepository.findByLoginId(loginId)
            ?: throw CoreException(ErrorType.ADMIN_NOT_FOUND, "[loginId = $loginId] 관리자를 찾을 수 없습니다.")

    /**
     * 권한을 가졌는지 확인하고, 가졌으면 그 관리자를 돌려준다 (P-43).
     *
     * 이름이 조건을 들고 있는 것은 `findAlive`·`getActiveOrThrow` 와 같은 이유다 (DS-3) —
     * 부르는 쪽이 확인을 건너뛸 수 없게 한다.
     */
    @Transactional(readOnly = true)
    fun requirePermission(adminUserId: Long, permission: AdminPermission): AdminUser {
        val admin = adminUserRepository.findById(adminUserId)
            ?: throw CoreException(ErrorType.ADMIN_NOT_FOUND, "[adminUserId = $adminUserId] 관리자를 찾을 수 없습니다.")

        return admin.also { guardHas(it, permission) }
    }

    /**
     * HTTP 경계가 아는 것은 숫자 id 가 아니라 `login_id` 다 — `Authentication.name` 이 그 값이다.
     *
     * `ROLE_ADMIN` 을 통과했어도 계정이 없으면 `ADMIN_NOT_FOUND` 다. 경계와 계정은 다른 것이다.
     */
    @Transactional(readOnly = true)
    fun requirePermission(loginId: AdminLoginId, permission: AdminPermission): AdminUser =
        getByLoginIdOrThrow(loginId).also { guardHas(it, permission) }

    /** 마스킹 해제 조회를 남긴다 (P-35 · D-12 접속기록). 목적 검사는 [PersonalDataAccessLog] 가 한다. */
    @Transactional
    fun recordPersonalDataAccess(actorId: Long, targetUserId: Long, purpose: String) {
        personalDataAccessLogRepository.save(
            PersonalDataAccessLog(actorId = actorId, targetUserId = targetUserId, purpose = purpose),
        )
    }

    private fun guardHas(admin: AdminUser, permission: AdminPermission) {
        if (!admin.has(permission)) {
            throw CoreException(ErrorType.ADMIN_PERMISSION_DENIED)
        }
    }

    @Transactional
    fun grantRole(targetId: Long, role: AdminRole, actorId: Long) {
        val target = resolveTargetOfRoleChange(targetId, actorId)
        target.grant(role)
        adminUserRepository.save(target)
        adminRoleHistoryRepository.save(AdminRoleHistory.granted(targetId, role, actorId))
    }

    @Transactional
    fun revokeRole(targetId: Long, role: AdminRole, actorId: Long) {
        val target = resolveTargetOfRoleChange(targetId, actorId)
        target.revoke(role)
        adminUserRepository.save(target)
        adminRoleHistoryRepository.save(AdminRoleHistory.revoked(targetId, role, actorId))
    }

    /**
     * 역할 변경의 두 전제를 확인한다 (P-44).
     *
     * 1. 바꾸는 사람이 [AdminPermission.ADMIN_MANAGE] 를 가졌는가 — D-12 의 최소 권한.
     * 2. **자기 자신을 대상으로 하지 않는가** — 권한 상승 방지.
     *
     * 2번이 따로 필요한 이유: 모든 변경이 이력에 남지만(P-44) **기록은 막지 못한다.**
     * 자기 권한을 올린 뒤 기록을 보는 사람이 자기 자신이면 그 기록은 통제 수단이 아니다.
     */
    private fun resolveTargetOfRoleChange(targetId: Long, actorId: Long): AdminUser {
        if (targetId == actorId) {
            throw CoreException(ErrorType.ADMIN_SELF_ROLE_CHANGE)
        }
        requirePermission(actorId, AdminPermission.ADMIN_MANAGE)

        return adminUserRepository.findById(targetId)
            ?: throw CoreException(ErrorType.ADMIN_NOT_FOUND, "[adminUserId = $targetId] 관리자를 찾을 수 없습니다.")
    }
}
