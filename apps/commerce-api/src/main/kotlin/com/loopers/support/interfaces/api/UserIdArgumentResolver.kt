package com.loopers.support.interfaces.api

import com.loopers.user.domain.LoginId
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** X-USER-ID 헤더를 요청자 식별자로 해석, 형식까지만 검사 (존재 확인은 application 담당) */
class UserIdArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == LoginId::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): LoginId {
        val raw = webRequest.getHeader(HEADER_NAME)
        return LoginId.parseOrNull(raw)
            ?: throw CoreException(ErrorType.USER_NOT_IDENTIFIED)
    }

    companion object {
        const val HEADER_NAME = "X-USER-ID"
    }
}
