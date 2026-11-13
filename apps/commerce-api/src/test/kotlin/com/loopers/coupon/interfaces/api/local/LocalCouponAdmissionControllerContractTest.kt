package com.loopers.coupon.interfaces.api.local

import com.loopers.config.AdminBoundaryConfig
import com.loopers.config.LocalCouponOperationsSecurity
import com.loopers.config.jackson.JacksonConfig
import com.loopers.coupon.application.CouponAdmissionOperations
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 운영 서비스 mock과 실제 보안 필터를 사용한 HTTP 경계 검증, Redis 복구 결과는 별도 통합 테스트 담당 */
@WebMvcTest(LocalCouponAdmissionController::class)
@Import(LocalCouponOperationsSecurity::class, AdminBoundaryConfig::class, JacksonConfig::class)
@ActiveProfiles("local")
@TestPropertySource(
    properties = [
        "coupon.operations.enabled=true",
        "coupon.operations.username=operator",
        "coupon.operations.password=operatorpass",
    ],
)
class LocalCouponAdmissionControllerContractTest @Autowired constructor(private val mockMvc: MockMvc) {
    @MockkBean
    private lateinit var operations: CouponAdmissionOperations

    private val endpoint = "/api-admin/v1/local/coupon-admission"
    private val closed = CouponGateStatus(false, emptySet(), "epoch-1", "startup_closed")

    @Test
    fun requestWithoutCredentialsReturns401() {
        mockMvc.perform(get(endpoint))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { operations.status() }
    }

    @Test
    fun wrongPasswordReturns401() {
        mockMvc.perform(get(endpoint).with(httpBasic("operator", "wrong-password")))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { operations.status() }
    }

    @Test
    fun customerHeaderCannotAuthenticateOperator() {
        mockMvc.perform(get(endpoint).header("X-USER-ID", "buyer1"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { operations.status() }
    }

    @Test
    fun adminRoleDoesNotGrantCouponOperatorAccess() {
        mockMvc.perform(get(endpoint).with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden)

        verify(exactly = 0) { operations.status() }
    }

    @Test
    fun validOperatorCanReadAdmissionStatus() {
        every { operations.status() } returns closed

        mockMvc.perform(get(endpoint).with(httpBasic("operator", "operatorpass")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.accepting").value(false))
            .andExpect(jsonPath("$.data.epoch").value("epoch-1"))
            .andExpect(jsonPath("$.data.activeTokens").isEmpty)

        verify(exactly = 1) { operations.status() }
    }

    @Test
    fun validOperatorCanCloseAdmission() {
        every { operations.close() } returns closed

        mockMvc.perform(post("$endpoint/close").with(httpBasic("operator", "operatorpass")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepting").value(false))

        verify(exactly = 1) { operations.close() }
    }

    @Test
    fun recoveryPassesExplicitConfirmation() {
        every { operations.recover(true) } returns closed.copy(accepting = true, reason = null)

        mockMvc.perform(
            post("$endpoint/recover").with(httpBasic("operator", "operatorpass"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"confirmed":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepting").value(true))

        verify(exactly = 1) { operations.recover(true) }
    }

    @Test
    fun recoveryWithoutConfirmationReturns400() {
        every { operations.recover(false) } throws CoreException(ErrorType.BAD_REQUEST)

        mockMvc.perform(
            post("$endpoint/recover").with(httpBasic("operator", "operatorpass"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"confirmed":false}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.BAD_REQUEST.code))

        verify(exactly = 1) { operations.recover(false) }
    }

    @Test
    fun omittedConfirmationDefaultsToFalse() {
        every { operations.recover(false) } throws CoreException(ErrorType.BAD_REQUEST)

        mockMvc.perform(
            post("$endpoint/recover").with(httpBasic("operator", "operatorpass"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 1) { operations.recover(false) }
    }

    @Test
    fun unresolvedRecoveryReturns409() {
        every { operations.recover(true) } throws CoreException(ErrorType.COUPON_ADMISSION_RECOVERY_CONFLICT)

        mockMvc.perform(
            post("$endpoint/recover").with(httpBasic("operator", "operatorpass"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"confirmed":true}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ADMISSION_RECOVERY_CONFLICT"))
    }
}
