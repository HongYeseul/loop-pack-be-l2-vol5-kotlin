package com.loopers.interfaces.api

import com.loopers.fixture.BrandFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 관리자 경계 (P-03).
 *
 * **거절 테스트에 유효한 CSRF 토큰을 실어 보냅니다.** 토큰이 없으면 CSRF 필터가 먼저 막아서
 * 403 이 나오고, 그러면 "역할 때문에 막혔다"를 확인한 것이 아니게 됩니다.
 * 토큰을 넣고도 403 이면 막은 것은 **역할**입니다.
 *
 * 반대로 관리자 요청에서 토큰만 빼면 403 이 나옵니다 — 그것이 CSRF 가 실제로 켜져 있다는 증거입니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ADMIN_ENDPOINT = "/api-admin/v1/brands"
        private const val JSON_BODY = """{"name":"루퍼스"}"""
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("관리자 역할의 요청은,")
    @Nested
    inner class Admin {
        @DisplayName("관리자 경계를 통과해 실제로 처리된다.")
        @Test
        fun passesAdminBoundary() {
            mockMvc.perform(get(ADMIN_ENDPOINT).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk)
        }

        @DisplayName("상태를 바꾸는 요청에 유효한 CSRF 토큰이 없으면, 역할이 맞아도 거절된다.")
        @Test
        fun rejectsStateChangingRequestWithoutCsrf() {
            val brand = brandJpaRepository.save(BrandFixture.brand())
            val admin = user("admin").roles("ADMIN")

            mockMvc.perform(post(ADMIN_ENDPOINT).with(admin).contentType(MediaType.APPLICATION_JSON).content(JSON_BODY))
                .andExpect(status().isForbidden)
            mockMvc.perform(
                put("$ADMIN_ENDPOINT/${brand.id}").with(admin).contentType(MediaType.APPLICATION_JSON).content(JSON_BODY),
            )
                .andExpect(status().isForbidden)
            mockMvc.perform(delete("$ADMIN_ENDPOINT/${brand.id}").with(admin))
                .andExpect(status().isForbidden)
        }
    }

    @DisplayName("일반 사용자 역할의 요청은,")
    @Nested
    inner class Customer {
        @DisplayName("관리자 경계에서 거절된다.")
        @Test
        fun rejectsAdminBoundary() {
            mockMvc.perform(get(ADMIN_ENDPOINT).with(user("customer").roles("USER")))
                .andExpect(status().isForbidden)
        }

        @DisplayName("유효한 CSRF 토큰을 넣어도 거절된다. 막는 것은 토큰이 아니라 역할이다.")
        @Test
        fun rejectsEvenWithValidCsrf() {
            val brand = brandJpaRepository.save(BrandFixture.brand())
            val customer = user("customer").roles("USER")

            mockMvc.perform(
                post(ADMIN_ENDPOINT).with(customer).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(JSON_BODY),
            )
                .andExpect(status().isForbidden)
            mockMvc.perform(
                put("$ADMIN_ENDPOINT/${brand.id}").with(customer).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(JSON_BODY),
            )
                .andExpect(status().isForbidden)
            mockMvc.perform(delete("$ADMIN_ENDPOINT/${brand.id}").with(customer).with(csrf()))
                .andExpect(status().isForbidden)
        }
    }

    @DisplayName("식별이 없는 요청은,")
    @Nested
    inner class Unidentified {
        @DisplayName("관리자 경계에서 거절된다.")
        @Test
        fun rejectsAdminBoundary() {
            mockMvc.perform(get(ADMIN_ENDPOINT))
                .andExpect(status().isForbidden)
        }

        @DisplayName("유효한 CSRF 토큰을 넣어도 거절된다.")
        @Test
        fun rejectsEvenWithValidCsrf() {
            mockMvc.perform(
                post(ADMIN_ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(JSON_BODY),
            )
                .andExpect(status().isForbidden)
        }
    }

    @DisplayName("고객 경로는,")
    @Nested
    inner class CustomerPath {
        @DisplayName("관리자 경계 밖이라 식별 없이도 처리된다. CSRF 토큰도 요구하지 않는다.")
        @Test
        fun staysOutsideAdminBoundary() {
            val brand = brandJpaRepository.save(BrandFixture.brand())

            mockMvc.perform(get("/api/v1/brands/${brand.id}"))
                .andExpect(status().isOk)
        }
    }
}
