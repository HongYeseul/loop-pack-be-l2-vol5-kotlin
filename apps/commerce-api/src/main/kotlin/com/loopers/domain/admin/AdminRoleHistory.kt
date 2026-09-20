package com.loopers.domain.admin

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class AdminRoleAction { GRANTED, REVOKED }

/**
 * 권한 부여·변경·말소 내역 (P-44).
 *
 * **D-12 가 3년 이상 보관하라고 한 것이 이것이다.** 그래서 append-only 이고 고치지 않는다 —
 * `PointTransaction` 원장(DS-12)과 같은 모양이다.
 *
 * 말소도 남긴다. 부여만 남기면 "지금 이 사람이 왜 이 권한을 갖고 있나"는 답할 수 있어도
 * "왜 잃었나"는 답할 수 없고, 그러면 기록이 현재 상태를 설명하지 못한다.
 */
@Entity
@Table(name = "admin_role_history")
class AdminRoleHistory(
    @Column(name = "admin_user_id", nullable = false, updatable = false)
    val adminUserId: Long,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, updatable = false, length = AdminUser.ROLE_MAX_LENGTH)
    val role: AdminRole,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action", nullable = false, updatable = false, length = ACTION_MAX_LENGTH)
    val action: AdminRoleAction,
    @Column(name = "actor_id", nullable = false, updatable = false)
    val actorId: Long,
) : BaseEntity() {
    companion object {
        const val ACTION_MAX_LENGTH = 20

        fun granted(adminUserId: Long, role: AdminRole, actorId: Long) =
            AdminRoleHistory(adminUserId, role, AdminRoleAction.GRANTED, actorId)

        fun revoked(adminUserId: Long, role: AdminRole, actorId: Long) =
            AdminRoleHistory(adminUserId, role, AdminRoleAction.REVOKED, actorId)
    }
}
