package com.loopers.domain.admin

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/** `Authentication.name` 에서 온 관리자 식별자, 로그인은 새로 만들지 않음 */
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
