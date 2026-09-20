package com.loopers.domain.admin

/**
 * 관리자 계정의 상태 (P-43).
 *
 * 고객의 [com.loopers.domain.user.UserStatus] 와 **생명주기가 다르다** — 가입·탈퇴가 아니라
 * 재직·정지·퇴사다. 같은 enum 으로 묶지 않은 이유다 (D-16).
 */
enum class AdminUserStatus {
    /** 재직. 권한이 실제로 작동하는 유일한 상태다. */
    ACTIVE,

    /** 정지. 휴직이나 조사 중. 역할은 남고 권한만 멈춘다. */
    SUSPENDED,

    /** 퇴사. 최종 상태다. 되돌리려면 계정을 새로 만든다. */
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
