package com.loopers.user.domain

/** 탈퇴는 파기 시계 시작, 차단은 해제 가능 (deletedAt 으로 표현 불가) */
enum class UserStatus {
    /** 이용 가능한 유일한 상태 */
    ACTIVE,

    /** 본인이 다시 켤 수 있음 */
    DEACTIVATED,

    /** 운영자 제재, 본인 해제 불가 */
    BLOCKED,

    /** 최종 상태, 복귀는 재가입 */
    WITHDRAWN,
    ;

    val isActive: Boolean get() = this == ACTIVE

    fun canTransitionTo(next: UserStatus): Boolean = next in allowedNext

    private val allowedNext: Set<UserStatus>
        get() = when (this) {
            ACTIVE -> setOf(DEACTIVATED, BLOCKED, WITHDRAWN)
            DEACTIVATED -> setOf(ACTIVE, BLOCKED, WITHDRAWN)
            // 차단 → 비활성화 전이 불가 (본인이 제재를 풀 수 없게)
            BLOCKED -> setOf(ACTIVE, WITHDRAWN)
            WITHDRAWN -> emptySet()
        }
}
