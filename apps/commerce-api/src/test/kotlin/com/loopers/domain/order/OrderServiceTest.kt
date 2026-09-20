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
 * **주문을 손에 쥐는 규칙** (P-02 · P-29).
 *
 * 확정·취소의 대상은 "있는 주문" 이 아니라 **"내 DRAFT 주문"** 이다. 조건을 이름이 들고 있으면
 * 부르는 쪽이 어느 질문인지 고르지 않을 수 없다 (DS-3 · `findAlive` 와 같은 이유).
 *
 * 남의 주문은 권한 오류가 아니라 **없는 대상**으로 답한다 (P-02) — 식별자를 나누면
 * 그 자체로 주문의 존재가 새어나간다 (DS-8).
 *
 * 목록의 정렬(P-46)은 id 계약이라 여기서 보지 않는다. 단위 테스트의 엔티티는 id 가 0 이다 —
 * `OrderRepositoryIntegrationTest` 가 본다.
 */
class OrderServiceTest {
    private class FakeOrderRepository : OrderRepository {
        private val stored = linkedMapOf<Long, Order>()
        private var sequence = 0L

        override fun save(order: Order): Order = order.also { stored[++sequence] = it }

        override fun findOwnedBy(orderId: Long, userId: Long): Order? = stored[orderId]?.takeIf { it.userId == userId }

        /** 계약: 최신 주문순 = id 내림차순 (P-46 · D-3). */
        override fun findOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> {
            val matched = stored.entries.filter { it.value.userId == userId }.sortedByDescending { it.key }.map { it.value }
            return PageResult(
                items = matched.drop(page.offset.toInt()).take(page.size),
                page = page.page,
                size = page.size,
                totalCount = matched.size.toLong(),
            )
        }

        /** 테스트가 id 를 알고 시작할 수 있게 한다. */
        fun seed(order: Order): Long = (++sequence).also { stored[it] = order }
    }

    private val orderRepository = FakeOrderRepository()
    private val orderService = OrderService(orderRepository)

    @DisplayName("확정할 주문을 찾을 때,")
    @Nested
    inner class GetDraftOwnedBy {
        @DisplayName("내 DRAFT 주문이면, 그대로 돌려준다 (P-26).")
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

        /** 권한 오류로 답하면 "그 주문은 있다" 가 새어나간다 (P-02 · DS-8). */
        @DisplayName("남의 주문이면, 없는 주문과 같은 오류로 답한다 (P-02).")
        @Test
        fun hidesOthersOrder() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 2L))

            // act & assert
            assertThat(assertThrows<CoreException> { orderService.getDraftOwnedByOrThrow(orderId = orderId, userId = 1L) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_FOUND)
        }

        /**
         * 만료 검사(설계 5절 ⑤)보다 **먼저** 걸러야 한다. 확정된 주문도 만료 시각은 지나 있어,
         * 순서가 바뀌면 이미 확정한 주문에 `ORDER_EXPIRED` 가 나간다.
         */
        @DisplayName("DRAFT 가 아니면, ORDER_NOT_DRAFT 로 거절한다 (P-29).")
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
        @DisplayName("확정·취소된 주문도 내 것이면 보인다 (C-11).")
        @Test
        fun returnsOwnOrderInAnyStatus() {
            // arrange
            val orderId = orderRepository.seed(OrderFixture.order(userId = 1L).apply { cancel(OrderFixture.BASE_TIME) })

            // act & assert
            assertThat(orderService.getOwnedByOrThrow(orderId = orderId, userId = 1L).status)
                .isEqualTo(OrderStatus.CANCELED)
        }

        @DisplayName("남의 주문이면, ORDER_NOT_FOUND 로 거절한다 (P-02).")
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
        @DisplayName("남의 주문은 섞이지 않는다 (P-02).")
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
