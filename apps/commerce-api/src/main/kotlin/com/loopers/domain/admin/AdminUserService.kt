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

    /** 권한 확인 후 관리자 반환, 이름에 조건을 담아 확인 누락 방지 */
    @Transactional(readOnly = true)
    fun requirePermission(adminUserId: Long, permission: AdminPermission): AdminUser {
        val admin = adminUserRepository.findById(adminUserId)
            ?: throw CoreException(ErrorType.ADMIN_NOT_FOUND, "[adminUserId = $adminUserId] 관리자를 찾을 수 없습니다.")

        return admin.also { guardHas(it, permission) }
    }

    /** 경계 통과와 계정 존재는 별개, 계정이 없으면 ADMIN_NOT_FOUND */
    @Transactional(readOnly = true)
    fun requirePermission(loginId: AdminLoginId, permission: AdminPermission): AdminUser =
        getByLoginIdOrThrow(loginId).also { guardHas(it, permission) }

    /** 마스킹 해제 조회 기록, 목적 검사는 PersonalDataAccessLog 담당 */
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

    /** ADMIN_MANAGE 보유 확인, 자기 자신 대상 변경 금지 (권한 상승 방지) */
    private fun resolveTargetOfRoleChange(targetId: Long, actorId: Long): AdminUser {
        if (targetId == actorId) {
            throw CoreException(ErrorType.ADMIN_SELF_ROLE_CHANGE)
        }
        requirePermission(actorId, AdminPermission.ADMIN_MANAGE)

        return adminUserRepository.findById(targetId)
            ?: throw CoreException(ErrorType.ADMIN_NOT_FOUND, "[adminUserId = $targetId] 관리자를 찾을 수 없습니다.")
    }
}
