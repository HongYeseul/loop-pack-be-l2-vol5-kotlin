package com.loopers.domain.user

/**
 * `domain` 이 저장소에 요구하는 것.
 *
 * **부분일치 검색이나 전수 목록 메서드를 두지 않는다** (P-34 · 설계 DS-6).
 * 없는 기능은 잘못 쓸 수 없다. 목적 없는 구매자 조회를 문서가 아니라 이 인터페이스가 막는다.
 */
interface UserRepository {
    fun findByLoginId(loginId: LoginId): User?
}
