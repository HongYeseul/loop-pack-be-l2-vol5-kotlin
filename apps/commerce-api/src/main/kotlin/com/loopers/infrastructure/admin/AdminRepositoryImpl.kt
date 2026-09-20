package com.loopers.infrastructure.admin

import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminRoleHistory
import com.loopers.domain.admin.AdminRoleHistoryRepository
import com.loopers.domain.admin.AdminUser
import com.loopers.domain.admin.AdminUserRepository
import com.loopers.domain.admin.PersonalDataAccessLog
import com.loopers.domain.admin.PersonalDataAccessLogRepository
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
