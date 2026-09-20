package com.loopers.interfaces.api.support

import com.loopers.domain.user.LoginId
import com.loopers.interfaces.api.ApiControllerAdvice
import com.loopers.interfaces.api.ApiResponse
import com.loopers.support.error.ErrorType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `X-USER-ID` 해석의 경계 (P-01 · 설계 DS-2).
 *
 * **형식 오류는 `interfaces`, 규칙 위반은 `domain`.** 그래서 이 resolver 는
 * 헤더가 있는지와 형식이 맞는지까지만 본다. **사용자가 실제로 있는지는 보지 않는다** —
 * 그건 `application` 의 일이고, 오류도 `USER_NOT_FOUND` 로 다르다 (설계 DS-8).
 *
 * 스프링 컨텍스트 없이 standalone MockMvc 로 돌린다. 이 규칙은 DB 와 무관하고,
 * 무관하다는 사실 자체가 이 테스트가 보여주려는 것이다.
 */
class UserIdArgumentResolverTest {
    @RestController
    class ProbeController {
        @GetMapping("/probe")
        fun probe(loginId: LoginId): ApiResponse<String> = ApiResponse.success(loginId.value)
    }

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(ProbeController())
        .setCustomArgumentResolvers(UserIdArgumentResolver())
        .setControllerAdvice(ApiControllerAdvice())
        .build()

    @DisplayName("헤더 값의 형식이 올바르면,")
    @Nested
    inner class ValidHeader {
        @DisplayName("요청자 식별자로 해석해 넘긴다.")
        @Test
        fun resolvesLoginId() {
            mockMvc.perform(get("/probe").header(UserIdArgumentResolver.HEADER_NAME, "user1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").value("user1"))
        }

        @DisplayName("없는 사용자여도 여기서는 통과시킨다. 존재 확인은 application 의 일이다.")
        @Test
        fun resolvesEvenWhenUserDoesNotExist() {
            mockMvc.perform(get("/probe").header(UserIdArgumentResolver.HEADER_NAME, "nobody9999"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").value("nobody9999"))
        }
    }

    @DisplayName("헤더가 없으면,")
    @Nested
    inner class MissingHeader {
        @DisplayName("USER_NOT_IDENTIFIED 로 거절한다.")
        @Test
        fun rejectsWithUserNotIdentified() {
            mockMvc.perform(get("/probe"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.USER_NOT_IDENTIFIED.code))
        }
    }

    @DisplayName("헤더 값의 형식이 어긋나면,")
    @Nested
    inner class MalformedHeader {
        @DisplayName("USER_NOT_IDENTIFIED 로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = ["", " ", "user 1", "user-1", "사용자1", "abcdefghij12345678901"])
        fun rejectsWithUserNotIdentified(raw: String) {
            mockMvc.perform(get("/probe").header(UserIdArgumentResolver.HEADER_NAME, raw))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.USER_NOT_IDENTIFIED.code))
        }
    }
}
