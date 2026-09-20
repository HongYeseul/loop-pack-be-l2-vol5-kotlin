package com.loopers.domain.admin

/**
 * **저장만 있다** (D-12 · 1~2년 보관). 지우거나 고치는 메서드를 두지 않는다 —
 * [AdminRoleHistoryRepository] 와 같은 판단이다.
 *
 * 읽는 메서드도 아직 없다. 월 1회 점검(D-12)이 들어올 때 그 질문의 모양대로 만든다.
 */
interface PersonalDataAccessLogRepository {
    fun save(log: PersonalDataAccessLog): PersonalDataAccessLog
}
