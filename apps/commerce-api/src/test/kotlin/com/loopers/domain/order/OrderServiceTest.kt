package com.loopers.domain.order

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.fixture.OrderFixture
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 확정·취소 대상은 "내 DRAFT 주문", 조건을 이름이 담음
 * 남의 주문은 권한 오류가 아닌 없는 대상으로 응답 (존재 노출 방지)
 * 목록 정렬은 실제 id 가 필요해 OrderRepositoryIntegrationTest 담당
 */
class OrderServiceTest {
    private class FakeOrderRepository : OrderRepository {
        private val stored = linkedMapOf<Long, Order>()
        private var sequence = 0L

        override fun save(order: Order): Order = order.also { stored[++sequence] = it }

        override fun findOwnedBy(orderId: Long, userId: Long): Order? = stored[orderId]?.takeIf { it.userId == userId }

        override fun findIgnoringOwner(orderId: Long): Order? = stored[orderId]

        /** 계약: 최신 주문순 = id 내림차순 */
        override fun findOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> {
            val matched = stored.entries.filter { it.value.userId == userId }.sortedByDescending { it.key }.map { it.value }
            return PageResult(
                items = matched.drop(page.offset.toInt()).take(page.size),
                page = page.page,
                size = page.size,
                totalCount = matched.size.toLong(),
            )
        }

        /** 테스트가 id 를 알고 시작하도록 지정 */
        fun seed(order: Order): Long = (++sequence).also { stored[it] = order }
    }

    private val orderRepository = FakeOrderRepository()
    private val orderService = OrderService(orderRepository)

    @DisplayName("확정할 주문을 찾을 때,")
    @Nested
    inner class GetDraftOwnedBy {
        @DisplayName("내 DRAFT 주문이면, 그대로 돌려준다.")
        @Test
        fun returnsOwnDraft() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 1L))

            // act & assert
            assertThat(orderService.getDraftOwnedByOrThrow(orderId = orderId, userId = 1L).status)
                .isEqualTo(OrderStatus.DRAFT)
        }

        @DisplayName("없는 주문이면, ORDER_NOT_FOUND 로 거절한다.")
        @Test
        fun rejectsMissing() {
            // act & assert
            assertThat(assertThrows<CoreException> { orderService.getDraftOwnedByOrThrow(orderId = 999L, userId = 1L) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_FOUND)
        }

        /** 권한 오류로 답하면 주문 존재가 노출됨 */
        @DisplayName("남의 주문이면, 없는 주문과 같은 오류로 답한다.")
        @Test
        fun hidesOthersOrder() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 2L))

            // act & assert
            assertThat(assertThrows<CoreException> { orderService.getDraftOwnedByOrThrow(orderId = orderId, userId = 1L) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_FOUND)
        }

        /** 만료 검사보다 먼저 상태로 걸러야 함, 확정 주문도 만료 시각은 지나 있음 */
        @DisplayName("DRAFT 가 아니면, ORDER_NOT_DRAFT 로 거절한다.")
        @Test
        fun rejectsNonDraft() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 1L).apply { confirm(OrderFixture.BASE_TIME) })

            // act & assert
            assertThat(assertThrows<CoreException> { orderService.getDraftOwnedByOrThrow(orderId = orderId, userId = 1L) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_DRAFT)
        }
    }

    @DisplayName("주문 하나를 조회할 때,")
    @Nested
    inner class GetOwnedBy {
        @DisplayName("확정·취소된 주문도 내 것이면 보인다.")
        @Test
        fun returnsOwnOrderInAnyStatus() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 1L).apply { cancel(OrderFixture.BASE_TIME) })

            // act & assert
            assertThat(orderService.getOwnedByOrThrow(orderId = orderId, userId = 1L).status)
                .isEqualTo(OrderStatus.CANCELED)
        }

        @DisplayName("남의 주문이면, ORDER_NOT_FOUND 로 거절한다.")
        @Test
        fun hidesOthersOrder() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 2L))

            // act & assert
            assertThat(assertThrows<CoreException> { orderService.getOwnedByOrThrow(orderId = orderId, userId = 1L) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_FOUND)
        }
    }

    @DisplayName("내 주문 목록을 볼 때,")
    @Nested
    inner class GetOrders {
        @DisplayName("남의 주문은 섞이지 않는다.")
        @Test
        fun excludesOthersOrders() {
            // arrange
            orderRepository.seed(OrderFixture.order(userId = 1L))
            orderRepository.seed(OrderFixture.order(userId = 2L))

            // act
            val result = orderService.getOrdersOwnedBy(userId = 1L, page = PageCriteria(0, 20))

            // assert
            assertThat(result.totalCount).isEqualTo(1L)
        }
    }
}
