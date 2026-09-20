package com.loopers.domain.user

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * `X-USER-ID` 헤더로 받는 사용자 식별자 (P-01 · 설계 DS-9).
 *
 * 형식을 여기 한 곳에만 둔다. `interfaces` 의 헤더 검사와 `User` 생성이 각자 형식을 적으면
 * 둘이 갈라지고, 그러면 헤더로는 통과하는데 저장할 수 없는 값이 생긴다.
 *
 * `interfaces` 가 이 타입을 쓰는 것은 ArchUnit 4번 규칙이 허용하는 **값** 의존이다 (설계 1-3절).
 * 막는 것은 `domain` 의 행동(`*Service`)과 저장(`*Repository`)이다.
 *
 * `@JvmInline value class` 로 두지 않는 이유: 코틀린이 value class 를 함수 시그니처에서 펼쳐
 * JVM 에는 `String` 으로 남긴다. 그러면 `UserIdArgumentResolver` 가 파라미터 타입으로
 * 이 타입을 알아볼 수 없다. 타입으로 식별되는 쪽이 더 중요해서 data class 로 둔다.
 */
data class LoginId(val value: String) {
    init {
        if (!FORMAT.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "사용자 식별자는 영문·숫자 1~${MAX_LENGTH}자여야 합니다.")
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 20
        private val FORMAT = Regex("^[A-Za-z0-9]{1,$MAX_LENGTH}$")

        /**
         * 형식만 보고 판단한다. 벗어나면 예외 대신 `null` 을 돌려주고,
         * **어떤 오류로 답할지는 부르는 쪽이 정한다** — 헤더라면 `USER_NOT_IDENTIFIED` (설계 DS-8).
         */
        fun parseOrNull(raw: String?): LoginId? = raw?.takeIf { FORMAT.matches(it) }?.let { LoginId(it) }
    }
}
