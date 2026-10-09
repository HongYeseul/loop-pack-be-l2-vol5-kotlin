package com.loopers.fixture

import com.loopers.domain.user.LoginId
import com.loopers.domain.user.User
import com.loopers.domain.user.UserStatus

/** 실습용 사용자 생성을 한 곳으로 모음 (회원가입이 없어 fixture 로 준비, 모양이 제각각이지 않게) */
object UserFixture {
    const val DEFAULT_LOGIN_ID = "user1"
    const val DEFAULT_DISPLAY_NAME = "실습용 사용자"

    fun user(
        loginId: String = DEFAULT_LOGIN_ID,
        displayName: String = DEFAULT_DISPLAY_NAME,
        status: UserStatus = UserStatus.ACTIVE,
    ): User = User(loginId = LoginId(loginId), displayName = displayName)
        .also { if (status != UserStatus.ACTIVE) it.changeStatus(status) }
}
