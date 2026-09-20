package com.loopers.infrastructure.admin

import com.loopers.domain.admin.AdminRoleHistory
import com.loopers.domain.admin.AdminUser
import com.loopers.domain.admin.PersonalDataAccessLog
import org.springframework.data.jpa.repository.JpaRepository

interface AdminUserJpaRepository : JpaRepository<AdminUser, Long> {
    fun findByLoginIdValue(loginIdValue: String): AdminUser?
}

/** 저장만 노출한다. 3년 보관 의무가 있는 기록이라 지우는 길을 만들지 않는다 (P-44). */
interface AdminRoleHistoryJpaRepository : JpaRepository<AdminRoleHistory, Long>

/** 저장만 노출한다. 1~2년 보관 의무가 있는 기록이라 지우는 길을 만들지 않는다 (D-12). */
interface PersonalDataAccessLogJpaRepository : JpaRepository<PersonalDataAccessLog, Long>
