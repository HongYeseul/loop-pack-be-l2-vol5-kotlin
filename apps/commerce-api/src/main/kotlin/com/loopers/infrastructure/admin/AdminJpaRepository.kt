package com.loopers.infrastructure.admin

import com.loopers.domain.admin.AdminRoleHistory
import com.loopers.domain.admin.AdminUser
import com.loopers.domain.admin.PersonalDataAccessLog
import org.springframework.data.jpa.repository.JpaRepository

interface AdminUserJpaRepository : JpaRepository<AdminUser, Long> {
    fun findByLoginIdValue(loginIdValue: String): AdminUser?
}

interface AdminRoleHistoryJpaRepository : JpaRepository<AdminRoleHistory, Long>

interface PersonalDataAccessLogJpaRepository : JpaRepository<PersonalDataAccessLog, Long>
