package com.loopers.domain.admin

/** 고객 UserStatus 와 생명주기가 달라 별도 enum */
enum class AdminUserStatus {
    /** 권한이 작동하는 유일한 상태 */
    ACTIVE,

    /** 휴직·조사 중, 역할은 두고 권한만 중지 */
    SUSPENDED,

    /** 최종 상태, 복귀는 계정 신규 생성 */
    RETIRED,
    ;

    val isActive: Boolean get() = this == ACTIVE

    fun canTransitionTo(next: AdminUserStatus): Boolean = next in allowedNext

    private val allowedNext: Set<AdminUserStatus>
        get() = when (this) {
            ACTIVE -> setOf(SUSPENDED, RETIRED)
            SUSPENDED -> setOf(ACTIVE, RETIRED)
            RETIRED -> emptySet()
        }
}
