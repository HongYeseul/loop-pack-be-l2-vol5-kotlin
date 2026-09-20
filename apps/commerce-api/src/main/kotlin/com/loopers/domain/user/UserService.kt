package com.loopers.domain.user

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UserService(
    private val userRepository: UserRepository,
) {
    /**
     * 요청자가 실제로 있는지 확인한다 (P-01).
     *
     * `UserIdArgumentResolver` 는 형식만 보고 통과시킨다 (설계 DS-2). 존재 확인은 여기부터이고,
     * 그래서 오류도 `USER_NOT_IDENTIFIED` 가 아니라 `USER_NOT_FOUND` 다 — 요청자가 고칠 대상이 다르다.
     */
    @Transactional(readOnly = true)
    fun getOrThrow(loginId: LoginId): User =
        userRepository.findByLoginId(loginId)
            ?: throw CoreException(ErrorType.USER_NOT_FOUND, "[loginId = $loginId] 사용자를 찾을 수 없습니다.")
}
