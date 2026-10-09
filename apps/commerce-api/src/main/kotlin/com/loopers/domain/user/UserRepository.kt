package com.loopers.domain.user

/** 부분일치·전수 목록 메서드 없음 (목적 없는 구매자 조회 차단) */
interface UserRepository {
    fun findByLoginId(loginId: LoginId): User?

    /** 상태로 거르지 않음, CS 는 차단·탈퇴 계정도 조회 */
    fun findById(id: Long): User?
}
