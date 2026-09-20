package com.loopers.domain.admin

/**
 * **저장만 있고 삭제가 없다** (P-44). 3년 보관 의무가 있는 기록이라
 * 지우는 메서드를 두지 않는다 — 없는 기능은 잘못 쓸 수 없다 (DS-6 과 같은 판단).
 */
interface AdminRoleHistoryRepository {
    fun save(history: AdminRoleHistory): AdminRoleHistory
}
