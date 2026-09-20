package com.loopers.interfaces.api.support

import com.loopers.domain.user.LoginId
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * `X-USER-ID` 헤더를 요청자 식별자로 해석한다 (P-01).
 *
 * **형식까지만 본다** (설계 DS-2). 사용자가 실제로 있는지는 `application` 이 확인하고,
 * 오류도 `USER_NOT_FOUND` 로 다르다 (설계 DS-8). 두 가지를 여기서 함께 보면
 * `interfaces` 가 저장소를 알게 되고, 요청자는 "헤더를 고쳐야 하는지 식별자를 고쳐야 하는지"를 구분할 수 없다.
 *
 * 컨트롤러는 파라미터 **타입** 으로 요청자를 받는다. 별도 애노테이션을 두지 않은 이유:
 * [LoginId] 타입의 컨트롤러 파라미터가 요청자 말고 다른 뜻일 수 없다.
 */
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
