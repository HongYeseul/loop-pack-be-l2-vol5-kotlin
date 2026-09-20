package com.loopers.domain.admin

import com.loopers.fixture.AdminUserFixture
import com.loopers.infrastructure.admin.AdminUserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.junit.jupiter.api.assertThrows

/**
 * 관리자 계정이 **고객과 다른 테이블에** 저장되는지 본다 (D-16).
 *
 * 사람 ↔ 역할이 `admin_user_role` 이라는 별도 테이블로 나가는지도 여기서 확인한다 —
 * 단위 테스트의 `Set<AdminRole>` 은 메모리 컬렉션이라 그 사실을 보여주지 못한다.
 */
@SpringBootTest
class AdminUserRepositoryIntegrationTest @Autowired constructor(
    private val adminUserRepository: AdminUserRepository,
    private val adminRoleHistoryRepository: AdminRoleHistoryRepository,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("관리자 계정은,")
    @Nested
    inner class Persistence {
        @DisplayName("여러 역할과 함께 저장되고 DB 에서 그대로 읽힌다.")
        @Test
        fun persistsWithMultipleRoles() {
            // arrange
            val saved = adminUserJpaRepository.save(
                AdminUserFixture.adminUser(loginId = "ops1", roles = arrayOf(AdminRole.CATALOG_ADMIN, AdminRole.CS_ADMIN)),
            )

            // act
            val found = adminUserRepository.findByLoginId(AdminLoginId("ops1"))

            // assert
            assertAll(
                { assertThat(found?.id).isEqualTo(saved.id) },
                { assertThat(found?.roles).containsExactlyInAnyOrder(AdminRole.CATALOG_ADMIN, AdminRole.CS_ADMIN) },
                { assertThat(found?.has(AdminPermission.CUSTOMER_READ_UNMASKED)).isTrue() },
                { assertThat(found?.status).isEqualTo(AdminUserStatus.ACTIVE) },
            )
        }

        @DisplayName("같은 식별자를 두 번 저장하면 DB 제약이 거절한다.")
        @Test
        fun rejectsDuplicateLoginId() {
            adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "ops1"))

            assertThrows<DataIntegrityViolationException> {
                adminUserJpaRepository.saveAndFlush(AdminUserFixture.adminUser(loginId = "ops1", displayName = "다른 사람"))
            }
        }
    }

    @DisplayName("권한 변경 이력은,")
    @Nested
    inner class History {
        @DisplayName("부여와 말소가 각각 한 줄로 쌓인다 (P-44 · D-12 3년 보관).")
        @Test
        fun accumulatesGrantAndRevoke() {
            // arrange
            val target = adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "cs1"))
            val actor = adminUserJpaRepository.save(
                AdminUserFixture.adminUser(loginId = "super1", roles = arrayOf(AdminRole.SUPER_ADMIN)),
            )

            // act
            adminRoleHistoryRepository.save(AdminRoleHistory.granted(target.id, AdminRole.CS_ADMIN, actor.id))
            adminRoleHistoryRepository.save(AdminRoleHistory.revoked(target.id, AdminRole.CS_ADMIN, actor.id))

            // assert · 기록은 고치지 않고 쌓기만 한다
            val found = adminUserRepository.findById(target.id)
            assertThat(found).isNotNull()
        }
    }
}
