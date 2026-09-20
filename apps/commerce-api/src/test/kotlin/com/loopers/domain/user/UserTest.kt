package com.loopers.domain.user

import com.loopers.domain.SoftDeletableEntity
import com.loopers.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 이번 주 `User` 는 **식별**만 맡는다 (설계 2-3절).
 * 잔액은 `Point` 가 따로 들고, 가입·탈퇴는 이번 범위 밖이라 fixture 로만 만들어진다.
 */
class UserTest {
    @DisplayName("사용자를 만들 때,")
    @Nested
    inner class Create {
        @DisplayName("식별자와 표시 이름을 그대로 보관한다.")
        @Test
        fun keepsLoginIdAndDisplayName() {
            // act
            val user = User(loginId = LoginId("user1"), displayName = "예슬")

            // assert
            assertAll(
                { assertThat(user.loginId).isEqualTo(LoginId("user1")) },
                { assertThat(user.displayName).isEqualTo("예슬") },
            )
        }

        @DisplayName("지울 수 없으므로, 지우는 방법도 갖지 않는다 (D-2 표 · DS-10).")
        @Test
        fun hasNoSoftDeleteCapability() {
            // assert · 컴파일 시점에 보장된다. User 는 SoftDeletableEntity 가 아니다.
            assertThat(SoftDeletableEntity::class.java.isAssignableFrom(User::class.java)).isFalse()
        }

        @DisplayName("표시 이름이 1~50자면 받아들인다.")
        @ParameterizedTest
        @ValueSource(ints = [1, 50])
        fun acceptsDisplayNameWithinRange(length: Int) {
            // arrange
            val displayName = "가".repeat(length)

            // act
            val user = User(loginId = LoginId("user1"), displayName = displayName)

            // assert
            assertThat(user.displayName).isEqualTo(displayName)
        }

        @DisplayName("표시 이름이 비었거나 공백뿐이거나 50자를 넘으면, 만들어지지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t"])
        fun rejectsBlankDisplayName(displayName: String) {
            // act & assert
            assertThrows<CoreException> { User(loginId = LoginId("user1"), displayName = displayName) }
        }

        @DisplayName("표시 이름이 50자를 넘으면, 만들어지지 않는다.")
        @Test
        fun rejectsTooLongDisplayName() {
            // arrange
            val displayName = "가".repeat(51)

            // act & assert
            assertThrows<CoreException> { User(loginId = LoginId("user1"), displayName = displayName) }
        }
    }
}
