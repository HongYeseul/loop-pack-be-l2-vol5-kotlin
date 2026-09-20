package com.loopers.interfaces.api.admin

import com.loopers.domain.admin.AdminRole
import com.loopers.domain.user.UserStatus
import com.loopers.fixture.AdminUserFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.admin.AdminUserJpaRepository
import com.loopers.infrastructure.admin.PersonalDataAccessLogJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A-14 · A-15 · 관리자의 구매자 조회 (P-34 · P-35 · DS-6).
 *
 * 여기서 확인하는 것 셋:
 * 1. 기본으로 나가는 값이 **마스킹되어 있다** (A-14).
 * 2. 해제 조회는 **목적 없이는 부를 수 없고**, 부르면 **기록이 한 줄 남는다** (A-15).
 * 3. 역할이 갈린다 — `ORDER_ADMIN` 은 마스킹된 것까지, `CS_ADMIN` 만 해제할 수 있다 (D-12 최소 권한).
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserAdminV1ApiE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userJpaRepository: UserJpaRepository,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val accessLogJpaRepository: PersonalDataAccessLogJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/users"
        private const val LOGIN_ID = "user1"
        private const val DISPLAY_NAME = "실습용 사용자"
        private const val MASKED_LOGIN_ID = "u****"
        private const val MASKED_DISPLAY_NAME = "실******"
        private const val PURPOSE = "CS-1234 배송지 확인"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun admin(loginId: String = "cs1", roles: Array<AdminRole> = arrayOf(AdminRole.CS_ADMIN)) =
        user(loginId).roles("ADMIN").also {
            adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = loginId, roles = roles))
        }

    private fun userId(status: UserStatus = UserStatus.ACTIVE): Long =
        userJpaRepository.save(UserFixture.user(loginId = LOGIN_ID, displayName = DISPLAY_NAME, status = status)).id

    @DisplayName("GET /api-admin/v1/users/{id} · 마스킹 조회 (A-14)")
    @Nested
    inner class GetMasked {
        @DisplayName("식별자와 표시 이름이 둘 다 가려진 채로 나온다 (P-35).")
        @Test
        fun masksPersonalFields() {
            // arrange
            val id = userId()

            // act & assert
            mockMvc.perform(get("$ENDPOINT/$id").with(admin(roles = arrayOf(AdminRole.ORDER_ADMIN))))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.loginId").value(MASKED_LOGIN_ID))
                .andExpect(jsonPath("$.data.displayName").value(MASKED_DISPLAY_NAME))
                .andExpect(jsonPath("$.data.status").value(UserStatus.ACTIVE.name))
        }

        @DisplayName("차단된 계정도 조회된다. CS 가 봐야 하는 것은 오히려 그쪽이다.")
        @Test
        fun includesBlockedUser() {
            // arrange
            val id = userId(status = UserStatus.BLOCKED)

            // act & assert
            mockMvc.perform(get("$ENDPOINT/$id").with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.status").value(UserStatus.BLOCKED.name))
        }

        @DisplayName("없는 사용자면 USER_NOT_FOUND 다.")
        @Test
        fun rejectsUnknownUser() {
            mockMvc.perform(get("$ENDPOINT/999").with(admin()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.USER_NOT_FOUND.code))
        }

        @DisplayName("개인정보에 닿지 않는 역할은 거절된다 (D-12 · 최소 권한).")
        @Test
        fun rejectsCatalogAdmin() {
            // arrange
            val id = userId()

            // act & assert
            mockMvc.perform(get("$ENDPOINT/$id").with(admin(roles = arrayOf(AdminRole.CATALOG_ADMIN))))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.ADMIN_PERMISSION_DENIED.code))
        }
    }

    @DisplayName("GET /api-admin/v1/users/{id}/unmasked · 해제 조회 (A-15)")
    @Nested
    inner class GetUnmasked {
        @DisplayName("목적을 실으면 원래 값이 나오고, 조회가 한 줄 기록된다 (P-35 · D-12).")
        @Test
        fun returnsRawValuesAndRecordsAccess() {
            // arrange
            val id = userId()
            val actor = adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = "cs9", roles = arrayOf(AdminRole.CS_ADMIN)))

            // act
            mockMvc.perform(get("$ENDPOINT/$id/unmasked").param("purpose", PURPOSE).with(user("cs9").roles("ADMIN")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.data.displayName").value(DISPLAY_NAME))

            // assert
            val log = accessLogJpaRepository.findAll().single()
            assertAll(
                { assertThat(log.actorId).isEqualTo(actor.id) },
                { assertThat(log.targetUserId).isEqualTo(id) },
                { assertThat(log.purpose).isEqualTo(PURPOSE) },
            )
        }

        @DisplayName("목적이 없으면 부를 수 없다. 기록도 남지 않는다.")
        @Test
        fun rejectsMissingPurpose() {
            // arrange
            val id = userId()

            // act & assert
            mockMvc.perform(get("$ENDPOINT/$id/unmasked").with(admin()))
                .andExpect(status().isBadRequest)
            assertThat(accessLogJpaRepository.count()).isZero()
        }

        @DisplayName("목적이 공백뿐이어도 거절한다. 적기만 한 목적은 목적이 아니다.")
        @Test
        fun rejectsBlankPurpose() {
            // arrange
            val id = userId()

            // act & assert
            mockMvc.perform(get("$ENDPOINT/$id/unmasked").param("purpose", "   ").with(admin()))
                .andExpect(status().isBadRequest)
            assertThat(accessLogJpaRepository.count()).isZero()
        }

        @DisplayName("마스킹까지만 볼 수 있는 역할은 거절된다. 기록도 남지 않는다 (D-12 · 최소 권한).")
        @Test
        fun rejectsOrderAdmin() {
            // arrange
            val id = userId()

            // act & assert
            mockMvc.perform(
                get("$ENDPOINT/$id/unmasked").param("purpose", PURPOSE)
                    .with(admin(roles = arrayOf(AdminRole.ORDER_ADMIN))),
            )
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.ADMIN_PERMISSION_DENIED.code))
            assertThat(accessLogJpaRepository.count()).isZero()
        }

        @DisplayName("없는 사용자면 USER_NOT_FOUND 이고, 기록도 남지 않는다.")
        @Test
        fun rejectsUnknownUser() {
            mockMvc.perform(get("$ENDPOINT/999/unmasked").param("purpose", PURPOSE).with(admin()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.USER_NOT_FOUND.code))
            assertThat(accessLogJpaRepository.count()).isZero()
        }
    }
}
