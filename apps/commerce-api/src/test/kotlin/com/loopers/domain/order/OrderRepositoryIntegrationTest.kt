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
 * 주문과 품목은 저장소가 따로라 order_item 을 직접 조회해 컬럼별 값까지 확인 (두 식별자가 모두 Long)
 * 목록 정렬은 실제 id 가 필요해 여기서 확인 (단위 테스트의 엔티티 id 는 0)
 */
@SpringBootTest
class OrderRepositoryIntegrationTest @Autowired constructor(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
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
        lines: List<OrderLine> = listOf(OrderFixture.line(productId = 7L, quantity = 2, unitPrice = 3_500L)),
        now: ZonedDateTime = OrderFixture.BASE_TIME,
    ): Order = orderRepository.save(OrderFixture.order(userId = userId, lines = lines, now = now))

    private fun singleRow(sql: String): Array<*> = entityManager.createNativeQuery(sql).singleResult as Array<*>

    private fun countRows(table: String): Long =
        (entityManager.createNativeQuery("SELECT COUNT(*) FROM $table").singleResult as Number).toLong()

    @DisplayName("주문을 저장하면,")
    @Nested
    inner class Saved {
        @DisplayName("주문만 저장된다. 품목은 품목 저장소로 따로 저장해야 한다.")
        @Test
        fun doesNotSaveItemsWithOrder() {
            // act
            save(lines = listOf(OrderFixture.line(productId = 7L), OrderFixture.line(productId = 9L)))

            // assert
            assertThat(countRows("order_item")).isZero()
        }

        @DisplayName("품목을 저장하면, 주문 식별자와 상품·수량·단가가 컬럼에 들어간다.")
        @Test
        fun savesItemColumns() {
            // arrange
            val order = save()

            // act
            orderItemRepository.saveAll(
                listOf(
                    OrderFixture.item(orderId = order.orderId, productId = 7L, quantity = 2, unitPrice = 3_500L),
                    OrderFixture.item(orderId = order.orderId, productId = 9L, quantity = 1, unitPrice = 1_000L),
                ),
            )

            // assert · product_id·order_id 가 모두 Long 이라 뒤바뀌어도 컴파일됨
            val row = singleRow("SELECT order_id, product_id, quantity, unit_price FROM order_item WHERE product_id = 7")
            assertAll(
                { assertThat((row[0] as Number).toLong()).isEqualTo(order.orderId) },
                { assertThat((row[1] as Number).toLong()).isEqualTo(7L) },
                { assertThat((row[2] as Number).toInt()).isEqualTo(2) },
                { assertThat((row[3] as Number).toLong()).isEqualTo(3_500L) },
                { assertThat(countRows("order_item")).isEqualTo(2L) },
            )
        }

        @DisplayName("만료 시각이 컬럼에 남고, 결제액은 비어 있다.")
        @Test
        fun storesExpiresAtAndNoPaidAmount() {
            // act
            save(now = OrderFixture.BASE_TIME)

            // assert · 계산이 아닌 저장값이라 정책이 바뀌어도 불변
            val row = singleRow("SELECT status, total_amount, paid_amount, expires_at FROM orders")
            assertAll(
                { assertThat(row[0]).isEqualTo("DRAFT") },
                { assertThat((row[1] as Number).toLong()).isEqualTo(7_000L) },
                { assertThat(row[2]).isNull() },
                { assertThat(row[3]).isNotNull() },
            )
        }

        @DisplayName("확정하면 결제액과 확정 시각이 채워진다.")
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

    @DisplayName("주문의 품목을 찾을 때,")
    @Nested
    inner class FindItems {
        @DisplayName("그 주문의 품목만, 담은 순서대로 찾는다.")
        @Test
        fun findsOnlyItemsOfTheOrderInInsertionOrder() {
            // arrange
            val order = save()
            val other = save()
            orderItemRepository.saveAll(
                listOf(
                    OrderFixture.item(orderId = order.orderId, productId = 9L),
                    OrderFixture.item(orderId = other.orderId, productId = 5L),
                    OrderFixture.item(orderId = order.orderId, productId = 7L),
                ),
            )

            // act
            val items = orderItemRepository.findAllByOrderId(order.orderId)

            // assert
            assertThat(items.map { it.productId }).containsExactly(9L, 7L)
        }

        @DisplayName("품목이 없는 주문이면 빈 목록이다.")
        @Test
        fun returnsEmptyWhenNoItems() {
            // arrange
            val order = save()

            // act & assert
            assertThat(orderItemRepository.findAllByOrderId(order.orderId)).isEmpty()
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

        /** 조건이 SQL 로 내려가야 함 (메모리 필터링은 찾았다가 버리는 경로 생성) */
        @DisplayName("남의 주문이면 찾아지지 않는다.")
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
        @DisplayName("최신 주문순이다.")
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

        /** id 는 유일해 동점 없음 */
        @DisplayName("페이지를 넘겨도 겹치지 않는다.")
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

        @DisplayName("남의 주문은 총 개수에도 들어가지 않는다.")
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
        @DisplayName("남의 주문도 돌려준다. 관리자에게는 \"내 것인가\" 가 조건이 아니다.")
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
