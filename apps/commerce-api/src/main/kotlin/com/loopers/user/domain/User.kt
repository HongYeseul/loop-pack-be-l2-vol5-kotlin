package com.loopers.user.domain

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 식별만 담당, 잔액·결제는 모름 */
@Entity
@Table(name = "user")
class User(
    loginId: LoginId,
    displayName: String,
) : BaseEntity() {
    @Column(name = "login_id", nullable = false, unique = true, length = LoginId.MAX_LENGTH)
    private var loginIdValue: String = loginId.value

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
    var displayName: String = displayName
        protected set

    /** varchar 저장 (STRING 만 쓰면 MySQL ENUM 이 생겨 상태 추가 때마다 ALTER 필요) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: UserStatus = UserStatus.ACTIVE
        protected set

    /** 밖으로는 값 객체만 노출 (검증 안 된 문자열 차단) */
    val loginId: LoginId
        get() = LoginId(loginIdValue)

    val maskedDisplayName: String get() = masked(displayName)

    /** BaseEntity.id 의 별칭, 호출부에서 무엇의 id 인지 구분 */
    val userId: Long get() = id

    init {
        if (displayName.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "표시 이름은 비어있을 수 없습니다.")
        }
        if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "표시 이름은 ${DISPLAY_NAME_MAX_LENGTH}자를 넘을 수 없습니다.")
        }
    }

    fun validateActive() {
        when (status) {
            UserStatus.ACTIVE -> Unit
            UserStatus.DEACTIVATED -> throw CoreException(ErrorType.USER_DEACTIVATED)
            UserStatus.BLOCKED -> throw CoreException(ErrorType.USER_BLOCKED)
            UserStatus.WITHDRAWN -> throw CoreException(ErrorType.USER_WITHDRAWN)
        }
    }

    /** 같은 상태로의 전이도 거절 (변경 없는 성공 응답은 오해 유발) */
    fun changeStatus(next: UserStatus) {
        if (!status.canTransitionTo(next)) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$status -> $next] 허용되지 않는 회원 상태 전이입니다.")
        }
        this.status = next
    }

    companion object {
        const val DISPLAY_NAME_MAX_LENGTH = 50
        const val STATUS_MAX_LENGTH = 20
    }
}
