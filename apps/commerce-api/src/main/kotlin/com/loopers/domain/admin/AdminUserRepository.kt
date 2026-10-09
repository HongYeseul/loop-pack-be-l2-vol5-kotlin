package com.loopers.domain.admin

interface AdminUserRepository {
    fun save(adminUser: AdminUser): AdminUser
    fun findById(id: Long): AdminUser?

    fun findByLoginId(loginId: AdminLoginId): AdminUser?
}
