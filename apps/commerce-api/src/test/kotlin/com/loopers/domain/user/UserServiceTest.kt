package com.loopers.domain.user

import com.loopers.fixture.UserFixture
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 요청자가 실제로 있는지 확인하는 자리 (P-01 · 설계 DS-8).
 *
 * `UserIdArgumentResolver` 가 형식만 보고 통과시킨 식별자를 여기서 조회한다.
 * 없으면 `USER_NOT_FOUND` — 헤더를 고쳐야 하는 `USER_NOT_IDENTIFIED` 와 요청자의 다음 행동이 다르다.
 *
 * 저장소를 가짜로 두고 도는 단위 테스트다. 확인하려는 것이 "못 찾으면 무엇을 던지나" 하나뿐이라
 * DB 를 띄울 이유가 없다 (설계 9절 · domain 단위).
 */
class UserServiceTest {
    private class FakeUserRepository(private val stored: List<User> = emptyList()) : UserRepository {
        override fun findByLoginId(loginId: LoginId): User? = stored.find { it.loginId == loginId }
    }

    @DisplayName("식별자로 사용자를 찾을 때,")
    @Nested
    inner class GetOrThrow {
        @DisplayName("있으면, 그 사용자를 돌려준다.")
        @Test
        fun returnsUser_whenUserExists() {
            // arrange
            val user = UserFixture.user(loginId = "user1")
            val userService = UserService(FakeUserRepository(listOf(user)))

            // act
            val found = userService.getActiveOrThrow(LoginId("user1"))

            // assert
            assertThat(found).isSameAs(user)
        }

        @DisplayName("없으면, USER_NOT_FOUND 로 거절한다.")
        @Test
        fun throwsUserNotFound_whenUserDoesNotExist() {
            // arrange
            val userService = UserService(FakeUserRepository())

            // act
            val exception = assertThrows<CoreException> { userService.getActiveOrThrow(LoginId("nobody")) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.USER_NOT_FOUND)
        }

        @DisplayName("차단된 계정이면, USER_BLOCKED 로 거절한다. 요청자는 자기 계정이므로 이유를 알아야 문의할 수 있다 (P-42).")
        @Test
        fun throwsUserBlocked_whenUserIsBlocked() {
            // arrange
            val user = UserFixture.user(loginId = "user1").apply { changeStatus(UserStatus.BLOCKED) }
            val userService = UserService(FakeUserRepository(listOf(user)))

            // act
            val exception = assertThrows<CoreException> { userService.getActiveOrThrow(LoginId("user1")) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.USER_BLOCKED)
        }

        @DisplayName("비활성화된 계정이면, USER_DEACTIVATED 로 거절한다. 본인이 다시 켤 수 있으므로 차단과 답이 다르다 (P-42).")
        @Test
        fun throwsUserDeactivated_whenUserIsDeactivated() {
            // arrange
            val user = UserFixture.user(loginId = "user1").apply { changeStatus(UserStatus.DEACTIVATED) }
            val userService = UserService(FakeUserRepository(listOf(user)))

            // act
            val exception = assertThrows<CoreException> { userService.getActiveOrThrow(LoginId("user1")) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.USER_DEACTIVATED)
        }

        @DisplayName("탈퇴한 계정이면, 탈퇴했다고 답한다. 없는 계정과 구분해야 재가입하면 된다는 것을 안다 (P-42).")
        @Test
        fun throwsUserWithdrawn_whenUserIsWithdrawn() {
            // arrange
            val user = UserFixture.user(loginId = "user1").apply { changeStatus(UserStatus.WITHDRAWN) }
            val userService = UserService(FakeUserRepository(listOf(user)))

            // act
            val exception = assertThrows<CoreException> { userService.getActiveOrThrow(LoginId("user1")) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.USER_WITHDRAWN)
        }

        @DisplayName("탈퇴한 계정과 아예 없는 계정은 서로 다른 오류다. 요청자가 할 일이 다르다 (DS-8).")
        @Test
        fun distinguishesWithdrawnFromAbsent() {
            // arrange
            val withdrawn = UserFixture.user(loginId = "user1").apply { changeStatus(UserStatus.WITHDRAWN) }
            val userService = UserService(FakeUserRepository(listOf(withdrawn)))

            // act
            val withdrawnError = assertThrows<CoreException> { userService.getActiveOrThrow(LoginId("user1")) }
            val absentError = assertThrows<CoreException> { userService.getActiveOrThrow(LoginId("nobody")) }

            // assert
            assertThat(withdrawnError.errorType).isNotEqualTo(absentError.errorType)
        }
    }
}
