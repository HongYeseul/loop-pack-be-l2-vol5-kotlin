package com.loopers.domain.user

import com.loopers.fixture.UserFixture
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 형식만 본 식별자를 여기서 조회, 없으면 USER_NOT_FOUND (요청자의 다음 행동이 다름) */
class UserServiceTest {
    private class FakeUserRepository(
        private val stored: List<User> = emptyList(),
        private val byId: Map<Long, User> = emptyMap(),
    ) : UserRepository {
        override fun findByLoginId(loginId: LoginId): User? = stored.find { it.loginId == loginId }

        // 단위 테스트의 엔티티 id 는 항상 0 이라 숫자 식별자를 별도로 지정
        override fun findById(id: Long): User? = byId[id]
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

        @DisplayName("차단된 계정이면, USER_BLOCKED 로 거절한다. 요청자는 자기 계정이므로 이유를 알아야 문의할 수 있다.")
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

        @DisplayName("비활성화된 계정이면, USER_DEACTIVATED 로 거절한다. 본인이 다시 켤 수 있으므로 차단과 답이 다르다.")
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

        @DisplayName("탈퇴한 계정이면, 탈퇴했다고 답한다. 없는 계정과 구분해야 재가입하면 된다는 것을 안다.")
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

        @DisplayName("탈퇴한 계정과 아예 없는 계정은 서로 다른 오류다. 요청자가 할 일이 다르다.")
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

    @DisplayName("숫자 식별자로 사용자를 찾을 때,")
    @Nested
    inner class GetByIdOrThrow {
        @DisplayName("상태로 거르지 않는다. CS 가 봐야 하는 것은 오히려 차단된 계정이다.")
        @Test
        fun returnsUserRegardlessOfStatus() {
            // arrange
            val blocked = UserFixture.user(loginId = "user1", status = UserStatus.BLOCKED)
            val userService = UserService(FakeUserRepository(byId = mapOf(7L to blocked)))

            // act
            val found = userService.getByIdOrThrow(7L)

            // assert
            assertThat(found).isSameAs(blocked)
        }

        @DisplayName("없으면 USER_NOT_FOUND 로 거절한다.")
        @Test
        fun throwsUserNotFound_whenUserDoesNotExist() {
            // arrange
            val userService = UserService(FakeUserRepository())

            // act
            val exception = assertThrows<CoreException> { userService.getByIdOrThrow(7L) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.USER_NOT_FOUND)
        }
    }
}
