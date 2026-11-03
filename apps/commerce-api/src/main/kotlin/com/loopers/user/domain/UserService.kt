package com.loopers.user.domain

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UserService(
    private val userRepository: UserRepository,
) {
    /** 이용 가능한 상태 확인, 상태마다 요청자가 할 일이 달라 오류 분리 */
    @Transactional(readOnly = true)
    fun getActiveOrThrow(loginId: LoginId): User {
        val user = userRepository.findByLoginId(loginId)
            ?: throw CoreException(ErrorType.USER_NOT_FOUND, "[loginId = $loginId] 사용자를 찾을 수 없습니다.")

        user.validateActive()
        return user
    }

    /** 상태를 보지 않음, CS 는 차단·탈퇴 계정도 조회 */
    @Transactional(readOnly = true)
    fun getByIdOrThrow(userId: Long): User =
        userRepository.findById(userId)
            ?: throw CoreException(ErrorType.USER_NOT_FOUND, "[userId = $userId] 사용자를 찾을 수 없습니다.")
}
