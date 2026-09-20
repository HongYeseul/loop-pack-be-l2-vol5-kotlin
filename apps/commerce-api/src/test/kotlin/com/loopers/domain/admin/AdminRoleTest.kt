package com.loopers.domain.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * 역할이 무엇을 할 수 있나 (P-43 · D-16).
 *
 * **이 매핑은 테이블이 아니라 코드다.** 역할의 권한 구성을 바꾸는 것은 정책 변경이라
 * 리뷰와 배포를 거쳐야 한다. 런타임에 DB 한 줄로 권한이 늘어나는 쪽이 더 위험하다.
 * D-12 가 요구하는 3년 보관은 "누가 어느 역할을 가졌나"이고, 그건 테이블로 남는다.
 */
class AdminRoleTest {
    @DisplayName("최소 권한 차등 부여 (D-12) 를 실제로 지키는지 — ")
    @Nested
    inner class LeastPrivilege {
        @DisplayName("상품·브랜드만 만지는 역할은 개인정보를 볼 수 없다.")
        @Test
        fun catalogAdminCannotReadPersonalData() {
            assertAll(
                { assertThat(AdminRole.CATALOG_ADMIN.permissions).contains(AdminPermission.CATALOG_WRITE) },
                { assertThat(AdminRole.CATALOG_ADMIN.permissions).doesNotContain(AdminPermission.CUSTOMER_READ_MASKED) },
                { assertThat(AdminRole.CATALOG_ADMIN.permissions).doesNotContain(AdminPermission.CUSTOMER_READ_UNMASKED) },
            )
        }

        @DisplayName("주문만 보는 역할은 마스킹을 해제할 수 없다. 해제는 CS 의 일이다 (P-35).")
        @Test
        fun orderAdminCannotUnmask() {
            assertAll(
                { assertThat(AdminRole.ORDER_ADMIN.permissions).contains(AdminPermission.CUSTOMER_READ_MASKED) },
                { assertThat(AdminRole.ORDER_ADMIN.permissions).doesNotContain(AdminPermission.CUSTOMER_READ_UNMASKED) },
            )
        }

        @DisplayName("CS 역할은 마스킹을 해제할 수 있지만, 상품을 고칠 수는 없다.")
        @Test
        fun csAdminUnmasksButCannotWriteCatalog() {
            assertAll(
                { assertThat(AdminRole.CS_ADMIN.permissions).contains(AdminPermission.CUSTOMER_READ_UNMASKED) },
                { assertThat(AdminRole.CS_ADMIN.permissions).doesNotContain(AdminPermission.CATALOG_WRITE) },
            )
        }

        @DisplayName("관리자와 역할을 관리하는 권한은 SUPER_ADMIN 에게만 있다.")
        @Test
        fun onlySuperAdminManagesAdmins() {
            val rolesWithManage = AdminRole.entries.filter { AdminPermission.ADMIN_MANAGE in it.permissions }

            assertThat(rolesWithManage).containsExactly(AdminRole.SUPER_ADMIN)
        }

        @DisplayName("SUPER_ADMIN 은 모든 권한을 가진다.")
        @Test
        fun superAdminHasEveryPermission() {
            assertThat(AdminRole.SUPER_ADMIN.permissions).containsExactlyInAnyOrderElementsOf(AdminPermission.entries)
        }
    }

    @DisplayName("어떤 역할도,")
    @Nested
    inner class EveryRole {
        @DisplayName("권한을 하나도 갖지 않는 채로 있지 않는다. 아무것도 못 하는 역할은 역할이 아니다.")
        @ParameterizedTest
        @EnumSource(AdminRole::class)
        fun hasAtLeastOnePermission(role: AdminRole) {
            assertThat(role.permissions).isNotEmpty()
        }
    }
}
