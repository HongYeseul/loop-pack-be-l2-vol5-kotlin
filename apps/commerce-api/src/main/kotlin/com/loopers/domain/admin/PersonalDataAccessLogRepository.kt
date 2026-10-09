package com.loopers.domain.admin

/** 저장만 노출, 1~2년 보관 의무 */
interface PersonalDataAccessLogRepository {
    fun save(log: PersonalDataAccessLog): PersonalDataAccessLog
}
