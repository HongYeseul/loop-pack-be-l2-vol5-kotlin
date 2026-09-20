package com.loopers.interfaces.api.support

import com.loopers.domain.admin.AdminLoginId
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * `ROLE_ADMIN` 경계를 통과한 요청의 `Authentication.name` 을 관리자 식별자로 해석한다 (P-03 · P-43).
 *
 * [UserIdArgumentResolver] 와 같은 모양이고 같은 이유다 — **형식까지만 본다.**
 * 그 이름의 계정이 있는지와 무엇을 할 수 있는지는 `application` 이 확인하고,
 * 오류도 `ADMIN_NOT_FOUND` · `ADMIN_PERMISSION_DENIED` 로 다르다 (DS-8).
 *
 * 관리자 엔드포인트가 열여섯 개라 각 컨트롤러가 `Authentication` 을 받아 직접 변환하면 같은 줄이
 * 열여섯 번 반복된다. 고객 쪽에 이미 있는 자리에 하나 더 두는 편이 싸다.
 */
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
