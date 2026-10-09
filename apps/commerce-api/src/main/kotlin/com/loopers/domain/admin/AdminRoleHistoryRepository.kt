package com.loopers.domain.admin

/** 저장만 노출, 3년 보관 의무 */
interface AdminRoleHistoryRepository {
    fun save(history: AdminRoleHistory): AdminRoleHistory
}
