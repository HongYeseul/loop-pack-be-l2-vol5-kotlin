package com.loopers.domain.user

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 이번 주의 `User` 는 **식별**만 맡는다 (설계 2-3절).
 *
 * - 잔액은 [com.loopers.domain.point] 가 따로 든다. `User` 가 결제 도메인을 알지 않게 하기 위해서다.
 * - 가입·탈퇴는 이번 범위 밖이라(기획 3-2절) 이 엔티티는 fixture 로만 만들어진다.
 * - 삭제 기능이 없다(D-2 표). 그래서 `SoftDeletableEntity` 가 아니라 `BaseEntity` 를 상속해
 *   **`deletedAt` 컬럼 자체를 갖지 않는다** (DS-10). 지울 수 없는 것은 지우는 방법도 없어야 한다.
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

    companion object {
        const val DISPLAY_NAME_MAX_LENGTH = 50
    }
}
