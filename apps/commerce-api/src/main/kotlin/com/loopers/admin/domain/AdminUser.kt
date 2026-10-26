package com.loopers.admin.domain

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 고객과 별도 테이블, 개인정보취급자 의무 범위를 분리하기 위함 */
@Entity
@Table(name = "admin_user")
class AdminUser(
    loginId: AdminLoginId,
    displayName: String,
) : BaseEntity() {
    @Column(name = "login_id", nullable = false, unique = true, length = AdminLoginId.MAX_LENGTH)
    private var loginIdValue: String = loginId.value

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
    var displayName: String = displayName
        protected set

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: AdminUserStatus = AdminUserStatus.ACTIVE
        protected set

    /** 역할은 정체성·이력이 없는 값이라 @ElementCollection, 이력은 AdminRoleHistory 담당 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "admin_user_role", joinColumns = [JoinColumn(name = "admin_user_id")])
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = ROLE_MAX_LENGTH)
    private val roleSet: MutableSet<AdminRole> = mutableSetOf()

    val loginId: AdminLoginId
        get() = AdminLoginId(loginIdValue)

    val roles: Set<AdminRole>
        get() = roleSet.toSet()

    /** 재직 중이 아니면 빈 집합, 복귀 때 복원할 역할은 유지 */
    val permissions: Set<AdminPermission>
        get() = if (status.isActive) roleSet.flatMapTo(mutableSetOf()) { it.permissions } else emptySet()

    init {
        if (displayName.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "표시 이름은 비어있을 수 없습니다.")
        }
        if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "표시 이름은 ${DISPLAY_NAME_MAX_LENGTH}자를 넘을 수 없습니다.")
        }
    }

    fun has(permission: AdminPermission): Boolean = permission in permissions

    /** 이미 가진 역할은 거절, 변경 없는 이력 방지 */
    fun grant(role: AdminRole) {
        guardActive()
        if (!roleSet.add(role)) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$role] 이미 가지고 있는 역할입니다.")
        }
    }

    /** 갖지 않은 역할은 거절 */
    fun revoke(role: AdminRole) {
        guardActive()
        if (!roleSet.remove(role)) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$role] 가지고 있지 않은 역할입니다.")
        }
    }

    fun changeStatus(next: AdminUserStatus) {
        if (!status.canTransitionTo(next)) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$status -> $next] 허용되지 않는 관리자 상태 전이입니다.")
        }
        this.status = next
    }

    private fun guardActive() {
        if (!status.isActive) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$status] 재직 중이 아닌 관리자의 역할은 바꿀 수 없습니다.")
        }
    }

    companion object {
        const val DISPLAY_NAME_MAX_LENGTH = 50
        const val STATUS_MAX_LENGTH = 20
        const val ROLE_MAX_LENGTH = 30
    }
}
