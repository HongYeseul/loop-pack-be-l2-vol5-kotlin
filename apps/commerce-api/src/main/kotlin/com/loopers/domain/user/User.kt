package com.loopers.domain.user

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

/**
 * 이번 주의 `User` 는 **식별**만 맡는다 (설계 2-3절).
 *
 * - 잔액은 [com.loopers.domain.point] 가 따로 든다. `User` 가 결제 도메인을 알지 않게 하기 위해서다.
 * - 가입·탈퇴는 이번 범위 밖이라(기획 3-2절) 이 엔티티는 fixture 로만 만들어진다.
 * - 삭제 기능이 없다(D-2 표). 그래서 `SoftDeletableEntity` 가 아니라 `BaseEntity` 를 상속해
 *   **`deletedAt` 컬럼 자체를 갖지 않는다** (DS-10). 지울 수 없는 것은 지우는 방법도 없어야 한다.
 * - 대신 [UserStatus] 를 든다. 탈퇴·차단은 "안 보이게 한다"가 아니라 **상태**다 (P-41 · D-15).
 */
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

    /**
     * `varchar` 로 저장한다. `@Enumerated(STRING)` 만 두면 Hibernate 가 MySQL 네이티브 `ENUM` 을 만드는데,
     * 그러면 상태를 하나 더할 때마다 `ALTER TABLE` 이 필요하다. 전이 규칙은 [UserStatus] 가 이미 지킨다.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: UserStatus = UserStatus.ACTIVE
        protected set

    /** 컬럼은 문자열이지만 밖으로는 값 객체로만 나간다. 검증되지 않은 문자열이 새지 않게 한다. */
    val loginId: LoginId
        get() = LoginId(loginIdValue)

    init {
        if (displayName.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "표시 이름은 비어있을 수 없습니다.")
        }
        if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "표시 이름은 ${DISPLAY_NAME_MAX_LENGTH}자를 넘을 수 없습니다.")
        }
    }

    /**
     * 회원 상태를 바꾼다 (P-41).
     *
     * 어느 상태에서 어디로 갈 수 있는지는 [UserStatus] 가 안다. 같은 상태로의 전이도 거절한다 —
     * 바뀌는 것이 없는 요청을 성공으로 답하면 요청자가 "바뀌었다"고 오해한다 (P-19 가 충전 0원을 거절한 것과 같다).
     */
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
