package com.loopers.interfaces.api.admin

import com.loopers.domain.admin.AdminRole
import com.loopers.domain.order.OrderStatus
import com.loopers.fixture.AdminUserFixture
import com.loopers.fixture.OrderFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.admin.AdminUserJpaRepository
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A-12 · A-13 · 관리자의 주문 조회 (DS-6).
 *
 * A-12 는 **구매자를 지정해야 부를 수 있다** — CS 는 문의와 함께 주문번호나 사용자 식별자를 갖고
 * 시작하므로 그걸로 충분하고, 목적 없는 전수 조회를 만들지 않는다 (D-12 1번).
 * 딸려 나오는 구매자 정보는 **마스킹된 것**이다 (P-35).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderAdminV1ApiE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userJpaRepository: UserJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/orders"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun admin(loginId: String = "order1", roles: Array<AdminRole> = arrayOf(AdminRole.ORDER_ADMIN)) =
        user(loginId).roles("ADMIN").also {
            adminUserJpaRepository.save(AdminUserFixture.adminUser(loginId = loginId, roles = roles))
        }

    private fun userId(loginId: String = "user1", displayName: String = "실습용 사용자"): Long =
        userJpaRepository.save(UserFixture.user(loginId = loginId, displayName = displayName)).id

    private fun orderId(userId: Long, productId: Long = 7L): Long =
        orderJpaRepository.save(
            OrderFixture.order(userId = userId, items = listOf(OrderFixture.item(productId = productId))),
        ).id

    @DisplayName("GET /api-admin/v1/orders · 구매자별 주문 (A-12)")
    @Nested
    inner class GetOrders {
        @DisplayName("그 구매자의 주문만 최신순으로 나오고, 구매자는 마스킹되어 함께 나온다.")
        @Test
        fun returnsOrdersOfOneBuyerWithMaskedUser() {
            // arrange
            val buyer = userId()
            val other = userId(loginId = "user2", displayName = "다른 사람")
            val first = orderId(buyer)
            val second = orderId(buyer, productId = 8L)
            orderId(other)

            // act & assert
            mockMvc.perform(get(ENDPOINT).param("userId", buyer.toString()).with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.user.loginId").value("u****"))
                .andExpect(jsonPath("$.data.user.displayName").value("실******"))
                .andExpect(jsonPath("$.data.orders.totalCount").value(2))
                .andExpect(jsonPath("$.data.orders.items[*].id", contains(second.toInt(), first.toInt())))
        }

        @DisplayName("구매자를 지정하지 않으면 부를 수 없다 (D-12 1번).")
        @Test
        fun rejectsMissingUserId() {
            mockMvc.perform(get(ENDPOINT).with(admin()))
                .andExpect(status().isBadRequest)
        }

        @DisplayName("없는 구매자면 USER_NOT_FOUND 다.")
        @Test
        fun rejectsUnknownUser() {
            mockMvc.perform(get(ENDPOINT).param("userId", "999").with(admin()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.USER_NOT_FOUND.code))
        }

        @DisplayName("주문에 닿지 않는 역할은 거절된다 (D-12 · 최소 권한).")
        @Test
        fun rejectsCatalogAdmin() {
            val buyer = userId()

            mockMvc.perform(
                get(ENDPOINT).param("userId", buyer.toString()).with(admin(roles = arrayOf(AdminRole.CATALOG_ADMIN))),
            )
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.ADMIN_PERMISSION_DENIED.code))
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{id} · 상세 (A-13)")
    @Nested
    inner class GetOrder {
        @DisplayName("누구의 주문이든 품목까지 보인다. 관리자에게는 \"내 것인가\" 가 조건이 아니다.")
        @Test
        fun returnsAnyonesOrderWithItems() {
            // arrange
            val buyer = userId()
            val id = orderId(buyer)

            // act & assert
            mockMvc.perform(get("$ENDPOINT/$id").with(admin()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.userId").value(buyer))
                .andExpect(jsonPath("$.data.status").value(OrderStatus.DRAFT.name))
                .andExpect(jsonPath("$.data.items[0].productId").value(7))
        }

        @DisplayName("없는 주문이면 ORDER_NOT_FOUND 다.")
        @Test
        fun rejectsUnknownOrder() {
            mockMvc.perform(get("$ENDPOINT/999").with(admin()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.meta.errorCode").value(ErrorType.ORDER_NOT_FOUND.code))
        }
    }
}
