package com.loopers.domain.admin

interface AdminUserRepository {
    fun save(adminUser: AdminUser): AdminUser
    fun findById(id: Long): AdminUser?

    /** `ROLE_ADMIN` 경계를 통과한 요청의 `Authentication.name` 을 계정으로 해석한다. */
    fun findByLoginId(loginId: AdminLoginId): AdminUser?
}
