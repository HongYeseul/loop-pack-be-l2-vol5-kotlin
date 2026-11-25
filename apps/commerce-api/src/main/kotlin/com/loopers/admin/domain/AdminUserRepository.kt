package com.loopers.admin.domain

import com.loopers.admin.AdminLoginId
import com.loopers.admin.AdminUser

interface AdminUserRepository {
    fun save(adminUser: AdminUser): AdminUser
    fun findById(id: Long): AdminUser?

    fun findByLoginId(loginId: AdminLoginId): AdminUser?
}
