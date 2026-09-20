package com.loopers.domain.user

/**
 * 회원 상태 (P-41).
 *
 * **`deletedAt` 이 아니라 상태로 두는 이유** (D-15):
 * 탈퇴는 "행을 안 보이게 한다"가 아니라 **개인정보 파기 시계를 시작한다**는 뜻이고,
 * 정상·차단·탈퇴는 서로 오가는 상태다 — 차단은 풀 수 있고 탈퇴는 최종이다.
 * `deletedAt` 은 되돌림이 `restore()` 하나뿐이라 이 구분을 담지 못한다.
 * 그래서 `User` 는 `SoftDeletableEntity` 가 아니라 `BaseEntity` 를 상속한 채 이 상태를 든다 (DS-10).
 *
 * `ProductStatus` 와 같은 모양이다 — 되돌릴 수 있는 것과 최종인 것이 섞여 있고,
 * 그 차이가 상태를 나누는 이유다.
 *
 * [DEACTIVATED] 와 [BLOCKED] 를 나누는 것은 **누가 풀 수 있는가** 다.
 * 비활성화는 본인이 다시 켜고, 차단은 고객센터를 거쳐야 한다.
 * 그래서 거절 응답도 갈린다 (P-42 · DS-8).
 */
enum class UserStatus {
    /** 정상. 서비스를 쓸 수 있는 유일한 상태다. */
    ACTIVE,

    /** 비활성화. **본인이 다시 켤 수 있다.** 휴면이나 잠시 쉬어가는 경우다. */
    DEACTIVATED,

    /** 차단. 운영자가 건 제재라 **본인이 풀 수 없다.** */
    BLOCKED,

    /** 탈퇴. 최종 상태다. 되살리는 것은 재가입이지 상태 변경이 아니다. */
    WITHDRAWN,
    ;

    val isActive: Boolean get() = this == ACTIVE

    fun canTransitionTo(next: UserStatus): Boolean = next in allowedNext

    private val allowedNext: Set<UserStatus>
        get() = when (this) {
            ACTIVE -> setOf(DEACTIVATED, BLOCKED, WITHDRAWN)
            DEACTIVATED -> setOf(ACTIVE, BLOCKED, WITHDRAWN)
            // 차단에서 비활성화로 가지 않는다. 운영자가 건 제재를
            // 본인이 내려놓는 모양으로 바꿀 수 있으면 그것은 제재가 아니다.
            BLOCKED -> setOf(ACTIVE, WITHDRAWN)
            WITHDRAWN -> emptySet()
        }
}
