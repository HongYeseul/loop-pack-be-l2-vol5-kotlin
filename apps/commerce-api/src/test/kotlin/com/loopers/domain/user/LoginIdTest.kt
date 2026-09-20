package com.loopers.domain.user

import com.loopers.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * `X-USER-ID` 로 받는 값의 형식 (P-01 · 설계 DS-9).
 *
 * 형식을 이 값 객체 한 곳에만 두는 이유: 같은 규칙을 `interfaces` 의 헤더 검사와
 * `User` 생성 두 곳에서 각각 적으면 둘이 갈라진다. 갈라지면 헤더로는 통과하는데
 * 저장할 수 없는 값이 생긴다.
 */
class LoginIdTest {
    @DisplayName("영문·숫자 1~20자는,")
    @Nested
    inner class Valid {
        @DisplayName("올바른 형식으로 받아들인다.")
        @ParameterizedTest
        @ValueSource(strings = ["a", "1", "user1", "USER1", "abcdefghij1234567890"])
        fun accepts(raw: String) {
            // act
            val loginId = LoginId(raw)

            // assert
            assertThat(loginId.value).isEqualTo(raw)
        }
    }

    @DisplayName("형식을 벗어난 값은,")
    @Nested
    inner class Invalid {
        @DisplayName("거절한다.")
        @ParameterizedTest
        @ValueSource(
            strings = [
                "", // 빈 값
                " ", // 공백만
                "user 1", // 중간 공백
                "user-1", // 허용하지 않는 기호
                "user_1",
                "사용자1", // 영문·숫자 아님
                "abcdefghij12345678901", // 21자
            ],
        )
        fun rejects(raw: String) {
            // act & assert
            assertThrows<CoreException> { LoginId(raw) }
        }
    }

    @DisplayName("형식만 보고 판단할 때,")
    @Nested
    inner class ParseOrNull {
        @DisplayName("올바른 형식이면 값 객체를 돌려준다.")
        @Test
        fun returnsLoginId_whenFormatIsValid() {
            // act
            val loginId = LoginId.parseOrNull("user1")

            // assert
            assertThat(loginId).isEqualTo(LoginId("user1"))
        }

        @DisplayName("형식을 벗어나면 예외가 아니라 null 을 돌려준다. 어떤 오류로 답할지는 부르는 쪽이 정한다.")
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = ["", " ", "user-1", "abcdefghij12345678901"])
        fun returnsNull_whenFormatIsInvalid(raw: String?) {
            // act
            val loginId = LoginId.parseOrNull(raw)

            // assert
            assertThat(loginId).isNull()
        }
    }
}
