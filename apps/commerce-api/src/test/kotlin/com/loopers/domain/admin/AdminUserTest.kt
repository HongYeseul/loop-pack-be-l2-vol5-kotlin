package com.loopers.domain.admin

import com.loopers.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

/**
 * 관리자 계정 (P-43).
 *
 * **고객(`User`)과 다른 테이블이다** (D-16). 생명주기가 다르고(가입·탈퇴 vs 입사·퇴사),
 * 무엇보다 개인정보보호법상 **개인정보취급자** 의무가 걸리는 쪽이 이쪽이다 (D-12).
 * 고객 테이블에 섞으면 그 범위를 잘라낼 수 없다.
 */
class AdminUserTest {
    @DisplayName("관리자를 만들 때,")
    @Nested
    inner class Create {
        @DisplayName("재직 상태로 시작하고, 역할은 비어 있다. 권한은 따로 받아야 한다.")
        @Test
        fun startsActiveWithNoRole() {
            // act
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")

            // assert
            assertAll(
                { assertThat(admin.status).isEqualTo(AdminUserStatus.ACTIVE) },
                { assertThat(admin.roles).isEmpty() },
                { assertThat(admin.permissions).isEmpty() },
            )
        }
    }

    @DisplayName("역할을 줄 때,")
    @Nested
    inner class GrantRole {
        @DisplayName("그 역할의 권한을 갖게 된다.")
        @Test
        fun gainsPermissionsOfRole() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")

            // act
            admin.grant(AdminRole.CATALOG_ADMIN)

            // assert
            assertAll(
                { assertThat(admin.roles).containsExactly(AdminRole.CATALOG_ADMIN) },
                { assertThat(admin.has(AdminPermission.CATALOG_WRITE)).isTrue() },
                { assertThat(admin.has(AdminPermission.CUSTOMER_READ_UNMASKED)).isFalse() },
            )
        }

        @DisplayName("역할을 여럿 가지면 권한은 합쳐진다.")
        @Test
        fun unionsPermissionsAcrossRoles() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")

            // act
            admin.grant(AdminRole.CATALOG_ADMIN)
            admin.grant(AdminRole.CS_ADMIN)

            // assert
            assertAll(
                { assertThat(admin.has(AdminPermission.CATALOG_WRITE)).isTrue() },
                { assertThat(admin.has(AdminPermission.CUSTOMER_READ_UNMASKED)).isTrue() },
            )
        }

        @DisplayName("이미 가진 역할을 또 주면 거절한다. 바뀌는 것이 없는 변경을 이력에 남기지 않는다.")
        @Test
        fun rejectsDuplicateGrant() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")
            admin.grant(AdminRole.CATALOG_ADMIN)

            // act & assert
            assertThrows<CoreException> { admin.grant(AdminRole.CATALOG_ADMIN) }
        }

        @DisplayName("재직 중이 아니면 역할을 줄 수 없다. 퇴사자에게 권한이 붙지 않는다 (D-12).")
        @Test
        fun rejectsWhenNotActive() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")
            admin.changeStatus(AdminUserStatus.RETIRED)

            // act & assert
            assertThrows<CoreException> { admin.grant(AdminRole.CATALOG_ADMIN) }
        }
    }

    @DisplayName("역할을 거둘 때,")
    @Nested
    inner class RevokeRole {
        @DisplayName("그 역할의 권한이 사라진다.")
        @Test
        fun losesPermissionsOfRole() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")
            admin.grant(AdminRole.CS_ADMIN)

            // act
            admin.revoke(AdminRole.CS_ADMIN)

            // assert
            assertAll(
                { assertThat(admin.roles).isEmpty() },
                { assertThat(admin.has(AdminPermission.CUSTOMER_READ_UNMASKED)).isFalse() },
            )
        }

        @DisplayName("갖지 않은 역할을 거두면 거절한다.")
        @Test
        fun rejectsRevokingRoleNotHeld() {
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")

            assertThrows<CoreException> { admin.revoke(AdminRole.CS_ADMIN) }
        }
    }

    @DisplayName("퇴사하면,")
    @Nested
    inner class Retired {
        @DisplayName("가진 역할과 무관하게 아무 권한도 없다. 업무가 바뀌면 지체 없이 말소한다 (D-12).")
        @Test
        fun hasNoPermissionRegardlessOfRoles() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")
            admin.grant(AdminRole.SUPER_ADMIN)

            // act
            admin.changeStatus(AdminUserStatus.RETIRED)

            // assert
            assertAll(
                { assertThat(admin.roles).containsExactly(AdminRole.SUPER_ADMIN) },
                { assertThat(admin.permissions).isEmpty() },
                { assertThat(admin.has(AdminPermission.CATALOG_WRITE)).isFalse() },
            )
        }

        @DisplayName("퇴사는 최종 상태다. 되돌리려면 새로 만든다.")
        @Test
        fun isFinal() {
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")
            admin.changeStatus(AdminUserStatus.RETIRED)

            assertThrows<CoreException> { admin.changeStatus(AdminUserStatus.ACTIVE) }
        }
    }

    @DisplayName("정지되면,")
    @Nested
    inner class Suspended {
        @DisplayName("권한이 멈추지만 역할은 남는다. 복귀하면 그대로 돌아온다.")
        @Test
        fun suspendsPermissionsButKeepsRoles() {
            // arrange
            val admin = AdminUser(loginId = AdminLoginId("admin1"), displayName = "운영자")
            admin.grant(AdminRole.CATALOG_ADMIN)

            // act
            admin.changeStatus(AdminUserStatus.SUSPENDED)

            // assert
            assertAll(
                { assertThat(admin.permissions).isEmpty() },
                { assertThat(admin.roles).containsExactly(AdminRole.CATALOG_ADMIN) },
            )

            // act · 복귀
            admin.changeStatus(AdminUserStatus.ACTIVE)

            // assert
            assertThat(admin.has(AdminPermission.CATALOG_WRITE)).isTrue()
        }
    }
}
