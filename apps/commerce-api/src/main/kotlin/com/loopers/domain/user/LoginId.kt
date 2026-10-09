package com.loopers.domain.user

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/** X-USER-ID 헤더 식별자, 형식 검증을 한 곳에 둠 (헤더 검사와 User 생성의 불일치 방지) */
data class LoginId(val value: String) {
    init {
        if (!FORMAT.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "사용자 식별자는 영문·숫자 1~${MAX_LENGTH}자여야 합니다.")
        }
    }

    /** 관리자 기본 노출용 마스킹 표현 */
    val masked: String get() = masked(value)

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 20
        private val FORMAT = Regex("^[A-Za-z0-9]{1,$MAX_LENGTH}$")

        /** 형식 위반 시 null, 오류 종류는 호출부가 결정 */
        fun parseOrNull(raw: String?): LoginId? = raw?.takeIf { FORMAT.matches(it) }?.let { LoginId(it) }
    }
}
