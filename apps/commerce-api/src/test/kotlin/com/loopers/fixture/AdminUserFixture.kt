package com.loopers.fixture

import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminRole
import com.loopers.domain.admin.AdminUser

object AdminUserFixture {
    fun adminUser(
        loginId: String = "admin1",
        displayName: String = "실습용 운영자",
        vararg roles: AdminRole,
    ): AdminUser = AdminUser(loginId = AdminLoginId(loginId), displayName = displayName)
        .also { user -> roles.forEach(user::grant) }
}
