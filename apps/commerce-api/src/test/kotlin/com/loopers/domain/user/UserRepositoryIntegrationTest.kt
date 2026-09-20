package com.loopers.domain.user

import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException

/**
 * 저장 구조가 설계대로 만들어졌는지 본다 (설계 7-1절).
 *
 * 테스트에 `@Transactional` 을 걸지 않고 `open-in-view: false` 라서, 저장과 조회가
 * **서로 다른 영속성 컨텍스트**에서 일어난다. 즉 아래 조회는 1차 캐시가 아니라 DB 에서 읽은 결과다.
 */
@SpringBootTest
class UserRepositoryIntegrationTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val userJpaRepository: UserJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("식별자로 사용자를 찾을 때,")
    @Nested
    inner class FindByLoginId {
        @DisplayName("저장된 사용자를 DB 에서 다시 읽어 돌려준다.")
        @Test
        fun returnsStoredUser() {
            // arrange
            val saved = userJpaRepository.save(UserFixture.user(loginId = "user1", displayName = "실습용 사용자"))

            // act
            val found = userRepository.findByLoginId(LoginId("user1"))

            // assert
            assertAll(
                { assertThat(found).isNotNull() },
                { assertThat(found?.id).isEqualTo(saved.id) },
                { assertThat(found?.loginId).isEqualTo(LoginId("user1")) },
                { assertThat(found?.displayName).isEqualTo("실습용 사용자") },
            )
        }

        @DisplayName("없는 식별자면, null 을 돌려준다. 오류로 바꾸는 것은 UserService 의 일이다.")
        @Test
        fun returnsNull_whenUserDoesNotExist() {
            // act
            val found = userRepository.findByLoginId(LoginId("nobody"))

            // assert
            assertThat(found).isNull()
        }
    }

    @DisplayName("회원 상태는,")
    @Nested
    inner class Status {
        @DisplayName("문자열로 저장되어 DB 에서 그대로 읽힌다 (P-41).")
        @Test
        fun isPersistedAsString() {
            // arrange
            userJpaRepository.save(UserFixture.user(loginId = "blocked1", status = UserStatus.BLOCKED))

            // act
            val found = userRepository.findByLoginId(LoginId("blocked1"))

            // assert
            assertThat(found?.status).isEqualTo(UserStatus.BLOCKED)
        }
    }

    @DisplayName("같은 식별자를 두 번 저장하면,")
    @Nested
    inner class UniqueLoginId {
        @DisplayName("DB 제약이 거절한다. 식별자는 사용자 하나만 가리킨다 (설계 7-1절 UNIQUE(login_id)).")
        @Test
        fun violatesUniqueConstraint() {
            // arrange
            userJpaRepository.save(UserFixture.user(loginId = "user1"))

            // act & assert
            assertThrows<DataIntegrityViolationException> {
                userJpaRepository.saveAndFlush(UserFixture.user(loginId = "user1", displayName = "다른 사람"))
            }
        }
    }
}
