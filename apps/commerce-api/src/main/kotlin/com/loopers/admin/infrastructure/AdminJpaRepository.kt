package com.loopers.admin.infrastructure

import com.loopers.admin.domain.AdminRoleHistory
import com.loopers.admin.domain.AdminUser
import com.loopers.admin.domain.PersonalDataAccessLog
import org.springframework.data.jpa.repository.JpaRepository

interface AdminUserJpaRepository : JpaRepository<AdminUser, Long> {
    fun findByLoginIdValue(loginIdValue: String): AdminUser?
}

interface AdminRoleHistoryJpaRepository : JpaRepository<AdminRoleHistory, Long>

interface PersonalDataAccessLogJpaRepository : JpaRepository<PersonalDataAccessLog, Long>
