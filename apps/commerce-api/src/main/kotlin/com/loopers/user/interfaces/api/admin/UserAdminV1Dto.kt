package com.loopers.user.interfaces.api.admin

import com.loopers.user.application.UserInfo
import com.loopers.user.application.UserUnmaskedInfo
import com.loopers.user.domain.UserStatus

class UserAdminV1Dto {
    /** 마스킹이 끝난 구매자, 여기서 다시 가리면 규칙이 두 곳 */
    data class UserResponse(
        val id: Long,
        val loginId: String,
        val displayName: String,
        val status: UserStatus,
    ) {
        companion object {
            fun from(info: UserInfo): UserResponse =
                UserResponse(id = info.id, loginId = info.loginId, displayName = info.displayName, status = info.status)
        }
    }

    /** 마스킹 해제 구매자, 응답 사실은 personal_data_access_log 에 기록 */
    data class UnmaskedUserResponse(
        val id: Long,
        val loginId: String,
        val displayName: String,
        val status: UserStatus,
    ) {
        companion object {
            fun from(info: UserUnmaskedInfo): UnmaskedUserResponse =
                UnmaskedUserResponse(id = info.id, loginId = info.loginId, displayName = info.displayName, status = info.status)
        }
    }
}
