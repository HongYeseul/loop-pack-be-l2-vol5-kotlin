package com.loopers.application.user

import com.loopers.domain.user.User
import com.loopers.domain.user.UserStatus

/**
 * A-14 · 관리자가 기본으로 보는 구매자 (P-34 · P-35).
 *
 * **마스킹된 값만 담는다** (DS-6). 다른 `Info` 들은 필요한 값을 전부 담고 `interfaces` 의 DTO 가
 * 가려내지만(DS-5), 개인정보는 예외다 — 재고가 새는 것과 개인정보가 새는 것은 대가가 다르다.
 * 원래 값이 필요한 자리는 [UserUnmaskedInfo] 이고, 거기에는 기록이 따라붙는다.
 */
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
