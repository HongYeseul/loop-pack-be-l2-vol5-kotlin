package com.loopers.admin.domain

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class AdminRoleAction { GRANTED, REVOKED }

/** 권한 부여·변경·말소 내역, 3년 보관 대상이라 append-only */
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
