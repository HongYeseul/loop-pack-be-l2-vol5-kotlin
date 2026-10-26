package com.loopers.user.application

import com.loopers.user.domain.User
import com.loopers.user.domain.UserStatus

/** 마스킹된 값만 담음, 원래 값은 UserUnmaskedInfo (조회 기록 동반) */
data class UserInfo(
    val id: Long,
    val loginId: String,
    val displayName: String,
    val status: UserStatus,
) {
    companion object {
        fun from(user: User): UserInfo = UserInfo(
            id = user.userId,
            loginId = user.loginId.masked,
            displayName = user.maskedDisplayName,
            status = user.status,
        )
    }
}
