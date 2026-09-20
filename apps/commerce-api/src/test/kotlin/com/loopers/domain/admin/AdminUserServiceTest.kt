package com.loopers.domain.admin

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

/**
 * 역할을 바꾸는 규칙 (P-44 · D-16).
 *
 * D-12 가 요구하는 것 둘을 여기서 지킨다 —
 * **권한 부여·변경·말소 내역 보관**(3년)과 **최소 권한**.
 * 그리고 기록만으로는 막지 못하는 것 하나를 더 막는다 — **자기 권한 올리기**.
 */
class AdminUserServiceTest {
    private class FakeAdminUserRepository : AdminUserRepository {
        private val stored = linkedMapOf<Long, AdminUser>()
        private var sequence = 0L

        override fun save(adminUser: AdminUser): AdminUser = adminUser
        override fun findById(id: Long): AdminUser? = stored[id]
        override fun findByLoginId(loginId: AdminLoginId): AdminUser? = stored.values.find { it.loginId == loginId }

        fun seed(adminUser: AdminUser): Long = (++sequence).also { stored[it] = adminUser }
    }

    private class FakeAdminRoleHistoryRepository : AdminRoleHistoryRepository {
        val saved = mutableListOf<AdminRoleHistory>()
        override fun save(history: AdminRoleHistory): AdminRoleHistory = history.also { saved.add(it) }
    }

    private class FakePersonalDataAccessLogRepository : PersonalDataAccessLogRepository {
        val saved = mutableListOf<PersonalDataAccessLog>()
        override fun save(log: PersonalDataAccessLog): PersonalDataAccessLog = log.also { saved.add(it) }
    }

    private val adminUserRepository = FakeAdminUserRepository()
    private val historyRepository = FakeAdminRoleHistoryRepository()
    private val accessLogRepository = FakePersonalDataAccessLogRepository()
    private val adminUserService = AdminUserService(adminUserRepository, historyRepository, accessLogRepository)

    private fun admin(loginId: String, vararg roles: AdminRole): AdminUser =
        AdminUser(loginId = AdminLoginId(loginId), displayName = "운영자 $loginId")
            .also { user -> roles.forEach(user::grant) }

    @DisplayName("역할을 줄 때,")
    @Nested
    inner class Grant {
        @DisplayName("관리 권한을 가진 사람이 남에게 주면 성공하고, 이력이 남는다 (D-12 · 3년 보관).")
        @Test
        fun grantsAndRecordsHistory() {
            // arrange
            val superAdminId = adminUserRepository.seed(admin("super1", AdminRole.SUPER_ADMIN))
            val targetId = adminUserRepository.seed(admin("cs1"))

            // act
            adminUserService.grantRole(targetId = targetId, role = AdminRole.CS_ADMIN, actorId = superAdminId)

            // assert
            val history = historyRepository.saved.single()
            assertAll(
                { assertThat(adminUserRepository.findById(targetId)?.roles).containsExactly(AdminRole.CS_ADMIN) },
                { assertThat(history.adminUserId).isEqualTo(targetId) },
                { assertThat(history.role).isEqualTo(AdminRole.CS_ADMIN) },
                { assertThat(history.action).isEqualTo(AdminRoleAction.GRANTED) },
                { assertThat(history.actorId).isEqualTo(superAdminId) },
            )
        }

        @DisplayName("관리 권한이 없으면 거절한다. CS 가 CS 에게 권한을 줄 수 없다 (D-12 · 최소 권한).")
        @Test
        fun rejectsActorWithoutManagePermission() {
            // arrange
            val csAdminId = adminUserRepository.seed(admin("cs1", AdminRole.CS_ADMIN))
            val targetId = adminUserRepository.seed(admin("cs2"))

            // act
            val exception = assertThrows<CoreException> {
                adminUserService.grantRole(targetId = targetId, role = AdminRole.CS_ADMIN, actorId = csAdminId)
            }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.ADMIN_PERMISSION_DENIED) },
                { assertThat(adminUserRepository.findById(targetId)?.roles).isEmpty() },
                { assertThat(historyRepository.saved).isEmpty() },
            )
        }

        @DisplayName("자기 자신에게는 줄 수 없다. **기록은 남기지만 막지는 못하므로** 따로 막는다.")
        @Test
        fun rejectsSelfGrant() {
            // arrange
            val superAdminId = adminUserRepository.seed(admin("super1", AdminRole.SUPER_ADMIN))

            // act
            val exception = assertThrows<CoreException> {
                adminUserService.grantRole(targetId = superAdminId, role = AdminRole.CS_ADMIN, actorId = superAdminId)
            }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.ADMIN_SELF_ROLE_CHANGE) },
                { assertThat(historyRepository.saved).isEmpty() },
            )
        }

        @DisplayName("정지된 관리자는 관리 권한이 멈춰 있어 줄 수 없다.")
        @Test
        fun rejectsSuspendedActor() {
            // arrange
            val suspended = admin("super1", AdminRole.SUPER_ADMIN).apply { changeStatus(AdminUserStatus.SUSPENDED) }
            val actorId = adminUserRepository.seed(suspended)
            val targetId = adminUserRepository.seed(admin("cs1"))

            // act & assert
            val exception = assertThrows<CoreException> {
                adminUserService.grantRole(targetId = targetId, role = AdminRole.CS_ADMIN, actorId = actorId)
            }
            assertThat(exception.errorType).isEqualTo(ErrorType.ADMIN_PERMISSION_DENIED)
        }
    }

    @DisplayName("역할을 거둘 때,")
    @Nested
    inner class Revoke {
        @DisplayName("말소도 이력에 남는다. 부여만 남기면 지금 권한을 설명할 수 없다.")
        @Test
        fun revokesAndRecordsHistory() {
            // arrange
            val superAdminId = adminUserRepository.seed(admin("super1", AdminRole.SUPER_ADMIN))
            val targetId = adminUserRepository.seed(admin("cs1", AdminRole.CS_ADMIN))

            // act
            adminUserService.revokeRole(targetId = targetId, role = AdminRole.CS_ADMIN, actorId = superAdminId)

            // assert
            val history = historyRepository.saved.single()
            assertAll(
                { assertThat(adminUserRepository.findById(targetId)?.roles).isEmpty() },
                { assertThat(history.action).isEqualTo(AdminRoleAction.REVOKED) },
                { assertThat(history.actorId).isEqualTo(superAdminId) },
            )
        }

        @DisplayName("자기 자신에게서도 거둘 수 없다. 자기 권한은 남이 바꾼다.")
        @Test
        fun rejectsSelfRevoke() {
            val superAdminId = adminUserRepository.seed(admin("super1", AdminRole.SUPER_ADMIN))

            assertThrows<CoreException> {
                adminUserService.revokeRole(targetId = superAdminId, role = AdminRole.SUPER_ADMIN, actorId = superAdminId)
            }
        }
    }

    @DisplayName("권한을 확인할 때,")
    @Nested
    inner class RequirePermission {
        @DisplayName("가지고 있으면 통과시킨다.")
        @Test
        fun passesWhenPermitted() {
            val id = adminUserRepository.seed(admin("catalog1", AdminRole.CATALOG_ADMIN))

            val found = adminUserService.requirePermission(id, AdminPermission.CATALOG_WRITE)

            assertThat(found.loginId).isEqualTo(AdminLoginId("catalog1"))
        }

        @DisplayName("없으면 ADMIN_PERMISSION_DENIED 로 거절한다.")
        @Test
        fun rejectsWhenNotPermitted() {
            val id = adminUserRepository.seed(admin("catalog1", AdminRole.CATALOG_ADMIN))

            val exception = assertThrows<CoreException> {
                adminUserService.requirePermission(id, AdminPermission.CUSTOMER_READ_UNMASKED)
            }

            assertThat(exception.errorType).isEqualTo(ErrorType.ADMIN_PERMISSION_DENIED)
        }

        @DisplayName("식별자로도 같은 답을 한다. HTTP 경계가 아는 것은 숫자 id 가 아니라 login_id 다.")
        @Test
        fun answersTheSameByLoginId() {
            adminUserRepository.seed(admin("catalog1", AdminRole.CATALOG_ADMIN))

            val found = adminUserService.requirePermission(AdminLoginId("catalog1"), AdminPermission.CATALOG_WRITE)
            val exception = assertThrows<CoreException> {
                adminUserService.requirePermission(AdminLoginId("catalog1"), AdminPermission.CUSTOMER_READ_UNMASKED)
            }

            assertAll(
                { assertThat(found.loginId).isEqualTo(AdminLoginId("catalog1")) },
                { assertThat(exception.errorType).isEqualTo(ErrorType.ADMIN_PERMISSION_DENIED) },
            )
        }

        @DisplayName("`ROLE_ADMIN` 은 통과했지만 계정이 없으면 ADMIN_NOT_FOUND 다. 경계와 계정은 다른 것이다.")
        @Test
        fun rejectsUnknownAdmin() {
            val exception = assertThrows<CoreException> {
                adminUserService.requirePermission(AdminLoginId("ghost1"), AdminPermission.CATALOG_READ)
            }

            assertThat(exception.errorType).isEqualTo(ErrorType.ADMIN_NOT_FOUND)
        }
    }

    @DisplayName("개인정보 조회를 기록할 때,")
    @Nested
    inner class RecordPersonalDataAccess {
        @DisplayName("누가·누구를·왜 를 남긴다 (P-35 · D-12 접속기록).")
        @Test
        fun recordsActorTargetAndPurpose() {
            // act
            adminUserService.recordPersonalDataAccess(actorId = 11L, targetUserId = 22L, purpose = "CS-1234 배송지 확인")

            // assert
            val log = accessLogRepository.saved.single()
            assertAll(
                { assertThat(log.actorId).isEqualTo(11L) },
                { assertThat(log.targetUserId).isEqualTo(22L) },
                { assertThat(log.purpose).isEqualTo("CS-1234 배송지 확인") },
            )
        }

        @DisplayName("목적이 비어 있으면 거절한다. 목적 없는 조회를 기록만 남기고 통과시키면 기록이 근거가 되지 못한다.")
        @Test
        fun rejectsBlankPurpose() {
            // act & assert
            assertThrows<CoreException> {
                adminUserService.recordPersonalDataAccess(actorId = 11L, targetUserId = 22L, purpose = "  ")
            }
            assertThat(accessLogRepository.saved).isEmpty()
        }
    }
}
