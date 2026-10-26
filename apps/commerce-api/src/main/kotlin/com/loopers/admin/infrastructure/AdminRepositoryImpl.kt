package com.loopers.admin.infrastructure

import com.loopers.admin.domain.AdminLoginId
import com.loopers.admin.domain.AdminRoleHistory
import com.loopers.admin.domain.AdminRoleHistoryRepository
import com.loopers.admin.domain.AdminUser
import com.loopers.admin.domain.AdminUserRepository
import com.loopers.admin.domain.PersonalDataAccessLog
import com.loopers.admin.domain.PersonalDataAccessLogRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class AdminUserRepositoryImpl(
    private val adminUserJpaRepository: AdminUserJpaRepository,
) : AdminUserRepository {
    override fun save(adminUser: AdminUser): AdminUser = adminUserJpaRepository.save(adminUser)

    override fun findById(id: Long): AdminUser? = adminUserJpaRepository.findByIdOrNull(id)

    override fun findByLoginId(loginId: AdminLoginId): AdminUser? =
        adminUserJpaRepository.findByLoginIdValue(loginId.value)
}

@Component
class AdminRoleHistoryRepositoryImpl(
    private val adminRoleHistoryJpaRepository: AdminRoleHistoryJpaRepository,
) : AdminRoleHistoryRepository {
    override fun save(history: AdminRoleHistory): AdminRoleHistory = adminRoleHistoryJpaRepository.save(history)
}

@Component
class PersonalDataAccessLogRepositoryImpl(
    private val personalDataAccessLogJpaRepository: PersonalDataAccessLogJpaRepository,
) : PersonalDataAccessLogRepository {
    override fun save(log: PersonalDataAccessLog): PersonalDataAccessLog = personalDataAccessLogJpaRepository.save(log)
}
