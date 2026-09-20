package com.loopers.application.user

import com.loopers.domain.user.User
import com.loopers.domain.user.UserStatus

/**
 * A-15 · 마스킹을 해제한 구매자 (P-35).
 *
 * [UserInfo] 와 필드가 같은데도 타입을 나눈 이유가 이것이다 — **마스킹된 경로가 실수로 원래 값을
 * 돌려줄 수 없다.** 합치면 `from` 을 어느 쪽으로 부르느냐가 유일한 차이가 되고, 그건 실수할 수 있다.
 */
data class UserUnmaskedInfo(
    val id: Long,
    val loginId: String,
    val displayName: String,
    val status: UserStatus,
) {
    companion object {
        fun from(user: User): UserUnmaskedInfo = UserUnmaskedInfo(
            id = user.userId,
            loginId = user.loginId.value,
            displayName = user.displayName,
            status = user.status,
        )
    }
}
