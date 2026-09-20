package com.loopers.domain.order

import com.loopers.domain.support.PageCriteria
import com.loopers.fixture.OrderFixture
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.ZonedDateTime

/**
 * 주문이 **저장 구조까지 그대로 내려가는지** 본다 (설계 7-1절).
 *
 * 품목은 `cascade` 로 함께 저장되므로(설계 2-2절) 루트만 저장하고 `order_item` 을 직접 읽어 확인한다 —
 * 두 식별자가 나란히 오는 자리라 **어느 컬럼에 무엇이 들어갔는지**까지 본다 (DS-13).
 *
 * 목록 정렬(P-46)도 여기서 본다. 단위 테스트의 엔티티는 id 가 0 이라 순서를 만들 수 없다.
 */
@SpringBootTest
class OrderRepositoryIntegrationTest @Autowired constructor(
    private val orderRepository: OrderRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val entityManager: EntityManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val USER_ID = 1L
        private const val OTHER_USER_ID = 2L
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun save(
        userId: Long = USER_ID,
        items: List<OrderItem> = listOf(OrderFixture.item(productId = 7L, quantity = 2, unitPrice = 3_500L)),
        now: ZonedDateTime = OrderFixture.BASE_TIME,
    ): Order = orderRepository.save(OrderFixture.order(userId = userId, items = items, now = now))

    private fun singleRow(sql: String): Array<*> = entityManager.createNativeQuery(sql).singleResult as Array<*>

    @DisplayName("주문을 저장하면,")
    @Nested
    inner class Saved {
        @DisplayName("품목이 함께 저장되고, 주문 식별자가 품목 쪽에 들어간다 (설계 2-2절 · cascade).")
        @Test
        fun cascadesItems() {
            // act
            val order = save(
                items = listOf(
                    OrderFixture.item(productId = 7L, quantity = 2, unitPrice = 3_500L),
                    OrderFixture.item(productId = 9L, quantity = 1, unitPrice = 1_000L),
                ),
            )

            // assert · product_id 와 order_id 가 둘 다 Long 이라 뒤바뀌어도 컴파일된다 (DS-13)
            val row = singleRow("SELECT order_id, product_id, quantity, unit_price FROM order_item WHERE product_id = 7")
            assertAll(
                { assertThat((row[0] as Number).toLong()).isEqualTo(order.orderId) },
                { assertThat((row[1] as Number).toLong()).isEqualTo(7L) },
                { assertThat((row[2] as Number).toInt()).isEqualTo(2) },
                { assertThat((row[3] as Number).toLong()).isEqualTo(3_500L) },
                {
                    assertThat(
                        (entityManager.createNativeQuery("SELECT COUNT(*) FROM order_item").singleResult as Number).toLong(),
                    ).isEqualTo(2L)
                },
            )
        }

        @DisplayName("만료 시각이 컬럼에 남고, 결제액은 비어 있다 (DS-4 · P-23).")
        @Test
        fun storesExpiresAtAndNoPaidAmount() {
            // act
            save(now = OrderFixture.BASE_TIME)

            // assert · 계산이 아니라 저장이므로, 10분이 바뀌어도 이 값은 그대로다
            val row = singleRow("SELECT status, total_amount, paid_amount, expires_at FROM orders")
            assertAll(
                { assertThat(row[0]).isEqualTo("DRAFT") },
                { assertThat((row[1] as Number).toLong()).isEqualTo(7_000L) },
                { assertThat(row[2]).isNull() },
                { assertThat(row[3]).isNotNull() },
            )
        }

        @DisplayName("확정하면 결제액과 확정 시각이 채워진다 (P-26 · P-28).")
        @Test
        fun storesPaidAmountOnConfirm() {
            // arrange
            val order = save()

            // act
            orderRepository.save(order.apply { confirm(OrderFixture.BASE_TIME.plusMinutes(1)) })

            // assert
            val row = singleRow("SELECT status, paid_amount, confirmed_at FROM orders")
            assertAll(
                { assertThat(row[0]).isEqualTo("CONFIRMED") },
                { assertThat((row[1] as Number).toLong()).isEqualTo(7_000L) },
                { assertThat(row[2]).isNotNull() },
            )
        }
    }

    @DisplayName("주문 하나를 찾을 때,")
    @Nested
    inner class FindOwnedBy {
        @DisplayName("내 주문이면 찾아진다.")
        @Test
        fun findsOwnOrder() {
            // arrange
            val order = save()

            // act & assert
            assertThat(orderRepository.findOwnedBy(orderId = order.orderId, userId = USER_ID)).isNotNull()
        }

        /** 조건이 SQL 로 내려가야 한다. 메모리에서 거르면 "찾았다가 버리는" 경로가 생긴다 (P-02). */
        @DisplayName("남의 주문이면 찾아지지 않는다 (P-02).")
        @Test
        fun hidesOthersOrder() {
            // arrange
            val order = save(userId = OTHER_USER_ID)

            // act & assert
            assertThat(orderRepository.findOwnedBy(orderId = order.orderId, userId = USER_ID)).isNull()
        }
    }

    @DisplayName("내 주문 목록은,")
    @Nested
    inner class FindOrders {
        @DisplayName("최신 주문순이다 (P-46).")
        @Test
        fun ordersByIdDesc() {
            // arrange
            val first = save()
            val second = save()
            val third = save()

            // act
            val result = orderRepository.findOrdersOwnedBy(userId = USER_ID, page = PageCriteria(0, 20))

            // assert
            assertAll(
                { assertThat(result.items.map { it.orderId }).containsExactly(third.orderId, second.orderId, first.orderId) },
                { assertThat(result.totalCount).isEqualTo(3L) },
            )
        }

        /** `id` 는 유일하므로 동점이 없다 — P-09 가 상품 목록에서 풀던 문제가 여기서는 생기지 않는다. */
        @DisplayName("페이지를 넘겨도 겹치지 않는다 (D-3).")
        @Test
        fun doesNotOverlapAcrossPages() {
            // arrange
            repeat(4) { save() }

            // act
            val firstPage = orderRepository.findOrdersOwnedBy(userId = USER_ID, page = PageCriteria(0, 2))
            val secondPage = orderRepository.findOrdersOwnedBy(userId = USER_ID, page = PageCriteria(1, 2))

            // assert
            assertAll(
                { assertThat(firstPage.items.map { it.orderId }).doesNotContainAnyElementsOf(secondPage.items.map { it.orderId }) },
                { assertThat(firstPage.totalCount).isEqualTo(4L) },
            )
        }

        @DisplayName("남의 주문은 총 개수에도 들어가지 않는다 (P-02).")
        @Test
        fun excludesOthersOrders() {
            // arrange
            save()
            save(userId = OTHER_USER_ID)

            // act
            val result = orderRepository.findOrdersOwnedBy(userId = USER_ID, page = PageCriteria(0, 20))

            // assert
            assertAll(
                { assertThat(result.items).hasSize(1) },
                { assertThat(result.totalCount).isEqualTo(1L) },
                { assertThat(orderJpaRepository.count()).isEqualTo(2L) },
            )
        }
    }

    @DisplayName("소유자를 따지지 않고 주문을 찾을 때,")
    @Nested
    inner class FindIgnoringOwner {
        @DisplayName("남의 주문도 돌려준다. 관리자에게는 \"내 것인가\" 가 조건이 아니다 (A-13).")
        @Test
        fun returnsAnyonesOrder() {
            // arrange
            val order = save(userId = OTHER_USER_ID)

            // act
            val found = orderRepository.findIgnoringOwner(order.orderId)

            // assert
            assertAll(
                { assertThat(found?.orderId).isEqualTo(order.orderId) },
                { assertThat(found?.userId).isEqualTo(OTHER_USER_ID) },
                { assertThat(orderRepository.findOwnedBy(order.orderId, USER_ID)).isNull() },
            )
        }

        @DisplayName("없는 주문이면 null 을 돌려준다.")
        @Test
        fun returnsNull_whenOrderDoesNotExist() {
            assertThat(orderRepository.findIgnoringOwner(999L)).isNull()
        }
    }
}
