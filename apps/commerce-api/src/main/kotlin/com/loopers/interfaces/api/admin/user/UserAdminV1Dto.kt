package com.loopers.interfaces.api.admin.user

import com.loopers.application.user.UserInfo
import com.loopers.application.user.UserUnmaskedInfo
import com.loopers.domain.user.UserStatus

class UserAdminV1Dto {
    /**
     * A-14 · 마스킹된 구매자 (P-34 · P-35).
     *
     * 가리는 일은 [UserInfo] 가 이미 끝냈다. 여기서 다시 가리면 규칙이 두 곳이 된다.
     */
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

    /** A-15 · 해제된 구매자 (P-35). 이 응답이 나갔다는 사실은 `personal_data_access_log` 에 남는다. */
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
