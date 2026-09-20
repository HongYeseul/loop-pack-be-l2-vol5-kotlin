package com.loopers.domain.user

/**
 * `domain` 이 저장소에 요구하는 것.
 *
 * **부분일치 검색이나 전수 목록 메서드를 두지 않는다** (P-34 · 설계 DS-6).
 * 없는 기능은 잘못 쓸 수 없다. 목적 없는 구매자 조회를 문서가 아니라 이 인터페이스가 막는다.
 */
interface UserRepository {
    fun findByLoginId(loginId: LoginId): User?

    /** 관리자 경로의 단건 조회 (A-14 · A-15). **상태로 거르지 않는다** — CS 가 봐야 하는 것은 오히려 차단·탈퇴한 계정이다. */
    fun findById(id: Long): User?
}
