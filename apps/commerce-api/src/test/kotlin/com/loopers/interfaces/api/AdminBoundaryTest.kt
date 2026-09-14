package com.loopers.interfaces.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    companion object {
        private const val ADMIN_ENDPOINT = "/api-admin/v1/brands"
        private const val CUSTOMER_ENDPOINT = "/api/v1/examples/1"
    }

    @DisplayName("관리자 역할의 요청은, 관리자 경계를 통과한다.")
    @Test
    fun passesAdminBoundary_whenRoleIsAdmin() {
        mockMvc.perform(get(ADMIN_ENDPOINT).with(user("admin").roles("ADMIN")))
            .andExpect { assertThat(it.response.status).isNotEqualTo(HttpStatus.FORBIDDEN.value()) }
    }

    @DisplayName("일반 사용자 역할의 요청은, 관리자 경계에서 거절된다.")
    @Test
    fun rejectsAdminBoundary_whenRoleIsUser() {
        mockMvc.perform(get(ADMIN_ENDPOINT).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden)
    }

    @DisplayName("식별이 없는 요청은, 관리자 경계에서 거절된다.")
    @Test
    fun rejectsAdminBoundary_whenUnidentified() {
        mockMvc.perform(get(ADMIN_ENDPOINT))
            .andExpect(status().isForbidden)
    }

    @DisplayName("고객 경로는 관리자 경계의 영향을 받지 않는다.")
    @Test
    fun leavesCustomerPathOutsideAdminBoundary() {
        mockMvc.perform(get(CUSTOMER_ENDPOINT))
            .andExpect { assertThat(it.response.status).isNotEqualTo(HttpStatus.FORBIDDEN.value()) }
    }
}
