package com.loopers.admin.domain

import com.loopers.admin.infrastructure.PersonalDataAccessLogJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** 두 식별자가 모두 Long 이라 뒤바뀌어도 통과하므로 컬럼별 값까지 확인 */
@SpringBootTest
class PersonalDataAccessLogRepositoryIntegrationTest @Autowired constructor(
    private val personalDataAccessLogRepository: PersonalDataAccessLogRepository,
    private val personalDataAccessLogJpaRepository: PersonalDataAccessLogJpaRepository,
    private val entityManager: EntityManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ACTOR_ID = 11L
        private const val TARGET_USER_ID = 22L
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("조회 기록을 저장하면,")
    @Nested
    inner class Saved {
        @DisplayName("요청자·대상·목적이 각자의 컬럼으로 내려간다.")
        @Test
        fun storesEachIdInItsOwnColumn() {
            // arrange
            personalDataAccessLogRepository.save(
                PersonalDataAccessLog(actorId = ACTOR_ID, targetUserId = TARGET_USER_ID, purpose = "CS-1234 배송지 확인"),
            )

            // act
            val row = entityManager
                .createNativeQuery("SELECT actor_id, target_user_id, purpose FROM personal_data_access_log")
                .singleResult as Array<*>

            // assert
            assertAll(
                { assertThat((row[0] as Number).toLong()).isEqualTo(ACTOR_ID) },
                { assertThat((row[1] as Number).toLong()).isEqualTo(TARGET_USER_ID) },
                { assertThat(row[2]).isEqualTo("CS-1234 배송지 확인") },
            )
        }

        @DisplayName("조회 시각이 함께 남는다. 기록의 값은 '언제' 가 있어야 생긴다 (1~2년 보관).")
        @Test
        fun storesAccessedAt() {
            // act
            val saved = personalDataAccessLogRepository.save(
                PersonalDataAccessLog(actorId = ACTOR_ID, targetUserId = TARGET_USER_ID, purpose = "CS-1234"),
            )

            // assert
            assertThat(saved.createdAt).isNotNull()
        }

        @DisplayName("같은 요청자가 같은 대상을 다시 보면 줄이 하나 더 쌓인다. 덮어쓰지 않는다 (append-only).")
        @Test
        fun appendsInsteadOfOverwriting() {
            // arrange
            repeat(2) {
                personalDataAccessLogRepository.save(
                    PersonalDataAccessLog(actorId = ACTOR_ID, targetUserId = TARGET_USER_ID, purpose = "CS-1234"),
                )
            }

            // act & assert
            assertThat(personalDataAccessLogJpaRepository.count()).isEqualTo(2L)
        }
    }
}
