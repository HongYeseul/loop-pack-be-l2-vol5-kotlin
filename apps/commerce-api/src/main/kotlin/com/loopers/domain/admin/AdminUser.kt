package com.loopers.domain.admin

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

/**
 * 관리자 계정 (P-43 · D-16).
 *
 * **고객(`User`)과 다른 테이블이다.** 이유 셋:
 * 1. 생명주기가 다르다 — 가입·탈퇴가 아니라 입사·정지·퇴사다.
 * 2. **법적 의무의 대상이 다르다.** D-12 의 의무(최소 권한 차등 부여, 권한 변경 내역 3년 보관,
 *    접속기록 1~2년 보관·월 1회 점검)는 **개인정보취급자**, 즉 이쪽에 걸린다.
 *    고객 테이블에 섞으면 그 범위를 잘라낼 수 없다.
 * 3. "회원 수"에 운영자가 섞이지 않는다.
 *
 * **로그인은 만들지 않는다** (과제 0-4절). 식별은 과제가 지정한 `ROLE_ADMIN` 경계 그대로이고,
 * 이 테이블은 "그 이름이 누구이고 무엇을 할 수 있나"만 답한다.
 *
 * 지우지 않으므로 `BaseEntity` 를 상속한다. 퇴사는 삭제가 아니라 상태다 (DS-10 · D-15 와 같은 축).
 */
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

    /**
     * 사람 ↔ 역할은 **테이블**이다 (`admin_user_role`). 한 사람이 여러 역할을 가질 수 있다.
     *
     * 역할은 값이지 엔티티가 아니라 `@ElementCollection` 으로 둔다 — 역할 자체에 정체성이나
     * 이력이 없기 때문이다. 이력이 필요한 것은 **부여·말소 행위**이고 그건 [AdminRoleHistory] 다.
     */
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

    /**
     * 가진 역할들의 권한 합집합. **재직 중이 아니면 비어 있다.**
     *
     * 역할을 지우지 않고 권한만 멈추는 이유: 정지는 복귀를 전제한다. 역할을 지우면
     * 복귀할 때 무엇을 돌려줘야 하는지 알 수 없고, 그 복원이 또 이력에 남지 않는다.
     * 퇴사도 같은 모양이라 "업무가 바뀌면 지체 없이 말소"(D-12)가 **상태 하나로** 지켜진다.
     */
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

    /** 역할을 준다. 이미 가진 역할을 또 주면 거절한다 — 바뀌는 것이 없는 변경을 이력에 남기지 않는다. */
    fun grant(role: AdminRole) {
        guardActive()
        if (!roleSet.add(role)) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$role] 이미 가지고 있는 역할입니다.")
        }
    }

    /** 역할을 거둔다. 갖지 않은 역할을 거두면 거절한다. */
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
