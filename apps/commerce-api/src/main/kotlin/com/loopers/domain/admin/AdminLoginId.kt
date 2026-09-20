package com.loopers.domain.admin

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 관리자 식별자.
 *
 * 이번 범위에서 이 값은 Spring Security 의 `Authentication.name` 에서 온다 —
 * 과제가 지정한 `ROLE_ADMIN` 경계 그대로이고, **로그인을 새로 만들지 않는다**(과제 0-4절).
 * 테이블이 하는 일은 "그 이름이 누구이고 무엇을 할 수 있나"를 답하는 것뿐이다.
 */
data class AdminLoginId(val value: String) {
    init {
        if (!FORMAT.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "관리자 식별자는 영문·숫자 1~${MAX_LENGTH}자여야 합니다.")
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 20
        private val FORMAT = Regex("^[A-Za-z0-9]{1,$MAX_LENGTH}$")

        fun parseOrNull(raw: String?): AdminLoginId? = raw?.takeIf { FORMAT.matches(it) }?.let { AdminLoginId(it) }
    }
}
