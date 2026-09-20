package com.loopers.fixture

import com.loopers.domain.user.LoginId
import com.loopers.domain.user.User

/**
 * 실습용 사용자를 만든다.
 *
 * 이번 주에는 회원가입이 없고(기획 3-2절) 과제가 "실습용 사용자는 fixture 로 준비할 수 있다"고 했다.
 * 그래서 `User` 를 만드는 자리는 여기 하나뿐이다 — 테스트마다 다른 모양의 사용자가 생기지 않게 한다.
 */
object UserFixture {
    const val DEFAULT_LOGIN_ID = "user1"
    const val DEFAULT_DISPLAY_NAME = "실습용 사용자"

    fun user(
        loginId: String = DEFAULT_LOGIN_ID,
        displayName: String = DEFAULT_DISPLAY_NAME,
    ): User = User(loginId = LoginId(loginId), displayName = displayName)
}
