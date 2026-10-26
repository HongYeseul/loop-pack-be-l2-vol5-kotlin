package com.loopers.user.application

import com.loopers.user.domain.User
import com.loopers.user.domain.UserStatus

/** UserInfo 와 필드가 같아도 타입 분리, 마스킹 경로가 원래 값을 반환하는 실수 방지 */
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
