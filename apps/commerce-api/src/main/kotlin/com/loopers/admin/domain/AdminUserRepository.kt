package com.loopers.admin.domain

interface AdminUserRepository {
    fun save(adminUser: AdminUser): AdminUser
    fun findById(id: Long): AdminUser?

    fun findByLoginId(loginId: AdminLoginId): AdminUser?
}
