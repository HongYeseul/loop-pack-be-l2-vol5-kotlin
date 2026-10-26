package com.loopers.support.interfaces.api

import com.loopers.admin.domain.AdminLoginId
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** Authentication.name 을 관리자 식별자로 해석, 형식까지만 검사 (계정·권한은 application 담당) */
class AdminLoginIdArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AdminLoginId::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AdminLoginId {
        val name = SecurityContextHolder.getContext().authentication?.name
        return AdminLoginId.parseOrNull(name)
            ?: throw CoreException(ErrorType.ADMIN_NOT_FOUND, "[name = $name] 관리자 식별자를 해석할 수 없습니다.")
    }
}
