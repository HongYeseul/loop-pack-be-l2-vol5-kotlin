package com.loopers.application.order

import com.loopers.domain.order.OrderService
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.user.LoginId
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.OrderFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.point.PointTransactionJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.ZonedDateTime

/**
 * **확정의 협력과 순서** (설계 5절 · 9절 application 통합).
 *
 * 여기서만 볼 수 있는 것은 셋이다.
 * - 거절되면 재고·잔액·주문 상태가 **모두** 원복되는가 (P-27) — 한 트랜잭션인지가 질문이라 가짜로는 못 본다.
 * - 둘 다 부족할 때 **재고 오류가 먼저 나가는가** (DS-7).
 * - 확정이 원장에 `order_id` 와 함께 남는가 (DS-12).
 *
 * 만료는 시계를 바꾸지 않고 **만료 시각이 지난 DRAFT 를 만들어** 확인한다 (DS-4) —
 * 판단 기준이 `expiresAt` 컬럼이라 그것만 과거면 된다.
 */
@SpringBootTest
class OrderFacadeIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val orderService: OrderService,
    private val pointService: PointService,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val loginId = LoginId(UserFixture.DEFAULT_LOGIN_ID)
    private var userId: Long = 0L
    private var brandId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = userJpaRepository.save(UserFixture.user()).userId
        brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun product(price: Long = 3_500L, stock: Int = 5, status: ProductStatus = ProductStatus.ON_SALE): Long =
        productJpaRepository.save(ProductFixture.product(brandId = brandId, price = price, stock = stock, status = status)).productId

    private fun command(productId: Long, quantity: Int = 2) =
        OrderCommand(items = listOf(OrderCommand.Item(productId = productId, quantity = quantity)))

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock

    private fun statusOf(orderId: Long): OrderStatus = orderJpaRepository.findById(orderId).get().status

    /** 만료 시각이 이미 지난 DRAFT. 확정 경로를 거치지 않고 만든다 — 시계가 아니라 컬럼이 기준이다 (DS-4). */
    private fun expiredDraft(productId: Long, quantity: Int = 2): Long =
        orderService.create(
            userId = userId,
            items = listOf(OrderFixture.item(productId = productId, quantity = quantity)),
            now = ZonedDateTime.now().minusMinutes(11),
        ).orderId

    @DisplayName("주문을 만들 때,")
    @Nested
    inner class Create {
        @DisplayName("단가를 상품에서 복사하고 합계를 만든다. 재고는 줄지 않는다 (P-23 · P-28 · D-13).")
        @Test
        fun copiesUnitPriceAndKeepsStock() {
            // arrange
            val productId = product(price = 3_500L, stock = 5)

            // act
            val order = orderFacade.create(loginId, command(productId, quantity = 2))

            // assert
            assertAll(
                { assertThat(order.status).isEqualTo(OrderStatus.DRAFT) },
                { assertThat(order.totalAmount).isEqualTo(7_000L) },
                { assertThat(order.items.single().unitPrice).isEqualTo(3_500L) },
                { assertThat(order.paidAmount).isNull() },
                { assertThat(stockOf(productId)).isEqualTo(5) },
            )
        }

        /** 재고보다 많이 담는 것은 생성에서 막지 않는다 — 재고를 잡지 않으므로 볼 이유가 없다 (D-13). */
        @DisplayName("재고보다 많은 수량도 담긴다. 거절은 확정에서 한다 (P-23 · D-13).")
        @Test
        fun allowsQuantityOverStock() {
            // arrange
            val productId = product(stock = 1)

            // act & assert
            assertThat(orderFacade.create(loginId, command(productId, quantity = 3)).totalAmount).isEqualTo(10_500L)
        }

        @DisplayName("판매중지·단종된 상품은 담을 수 없다 (P-38).")
        @Test
        fun rejectsNotPurchasableProduct() {
            // arrange
            val productId = product(status = ProductStatus.DISCONTINUED)

            // act
            val exception = assertThrows<CoreException> { orderFacade.create(loginId, command(productId)) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_PURCHASABLE)
        }

        /** 재고 0 은 판매 상태가 아니라 **재고**의 문제다. 담을 수는 있고 확정에서 `OUT_OF_STOCK` 이 된다 (DS-8). */
        @DisplayName("재고 0 인 판매중 상품은 담을 수 있다 (P-37 · DS-8).")
        @Test
        fun allowsSoldOutProduct() {
            // arrange
            val productId = product(stock = 0)

            // act & assert
            assertThat(orderFacade.create(loginId, command(productId)).status).isEqualTo(OrderStatus.DRAFT)
        }

        @DisplayName("삭제된 상품은 담을 수 없다 (P-24 · D-8).")
        @Test
        fun rejectsDeletedProduct() {
            // arrange
            val productId = product()
            productJpaRepository.save(productJpaRepository.findById(productId).get().apply { delete() })

            // act
            val exception = assertThrows<CoreException> { orderFacade.create(loginId, command(productId)) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }
    }

    @DisplayName("주문을 확정할 때,")
    @Nested
    inner class Confirm {
        @DisplayName("재고와 잔액이 함께 줄고, 원장에 이 주문이 남는다 (P-26 · DS-12).")
        @Test
        fun decreasesStockAndBalance() {
            // arrange
            val productId = product(price = 3_500L, stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id

            // act
            val confirmed = orderFacade.confirm(loginId, orderId)

            // assert
            assertAll(
                { assertThat(confirmed.status).isEqualTo(OrderStatus.CONFIRMED) },
                { assertThat(confirmed.paidAmount).isEqualTo(7_000L) },
                { assertThat(confirmed.balance).isEqualTo(3_000L) },
                { assertThat(stockOf(productId)).isEqualTo(3) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(3_000L) },
                { assertThat(pointTransactionJpaRepository.findAll().last().orderId).isEqualTo(orderId) },
            )
        }

        /** P-27 의 기대값 그대로다. 거절은 "안 된다고 답하는 것" 이 아니라 **아무것도 남기지 않는 것**이다. */
        @DisplayName("잔액이 부족하면 재고·잔액·상태가 모두 그대로다 (P-27).")
        @Test
        fun rollsBackEverything_whenBalanceIsNotEnough() {
            // arrange
            val productId = product(price = 3_500L, stock = 5)
            pointService.charge(userId, 1_000L)
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.INSUFFICIENT_BALANCE) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(1_000L) },
                { assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DRAFT) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(1L) },
            )
        }

        @DisplayName("재고가 부족하면 잔액은 건드리지 않는다 (P-27).")
        @Test
        fun keepsBalance_whenStockIsNotEnough() {
            // arrange
            val productId = product(stock = 1)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.OUT_OF_STOCK) },
                { assertThat(stockOf(productId)).isEqualTo(1) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(10_000L) },
            )
        }

        /**
         * 설계가 기획 대신 정한 것이다 (DS-7). 잔액 부족은 고객이 충전해서 풀 수 있지만
         * 재고 부족은 할 수 있는 것이 없다 — **해결할 수 없는 쪽을 먼저 알린다.**
         */
        @DisplayName("둘 다 부족하면 재고 부족을 먼저 알린다 (DS-7).")
        @Test
        fun reportsOutOfStockFirst() {
            // arrange
            val productId = product(stock = 1)
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.OUT_OF_STOCK)
        }

        /** 설계 6-4절 기대값 — 잔액 0 · 합계 0 */
        @DisplayName("합계가 0원이면 잔액이 0이어도 확정되고, 재고는 차감된다 (P-30 · D-11).")
        @Test
        fun confirmsZeroAmountOrder() {
            // arrange
            val productId = product(price = 0L, stock = 5)
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id

            // act
            val confirmed = orderFacade.confirm(loginId, orderId)

            // assert · 0원이어도 물건은 나간다
            assertAll(
                { assertThat(confirmed.paidAmount).isZero() },
                { assertThat(confirmed.balance).isZero() },
                { assertThat(stockOf(productId)).isEqualTo(3) },
                { assertThat(pointTransactionJpaRepository.findAll().single().orderId).isEqualTo(orderId) },
            )
        }

        /** 설계 6-4절 기대값 — 생성 10분 1초 뒤 */
        @DisplayName("만료된 DRAFT 는 거절되고, 재고·잔액이 그대로다 (P-32).")
        @Test
        fun rejectsExpiredDraft() {
            // arrange
            val productId = product(stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = expiredDraft(productId)

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert · 상태를 바꾸는 것은 배치가 한다 (DS-4 · 설계 5절 ⑤)
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_EXPIRED) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(10_000L) },
                { assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DRAFT) },
            )
        }

        @DisplayName("DRAFT 를 만든 뒤 상품이 단종되면, 확정을 거절한다 (P-38).")
        @Test
        fun rejectsDiscontinuedAfterDraft() {
            // arrange
            val productId = product(stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(loginId, command(productId)).id
            productJpaRepository.save(
                productJpaRepository.findById(productId).get().apply { changeStatus(ProductStatus.DISCONTINUED) },
            )

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_PURCHASABLE) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
            )
        }

        @DisplayName("DRAFT 를 만든 뒤 상품이 삭제되면, 확정을 거절한다 (D-8).")
        @Test
        fun rejectsDeletedAfterDraft() {
            // arrange
            val productId = product(stock = 5)
            val orderId = orderFacade.create(loginId, command(productId)).id
            productJpaRepository.save(productJpaRepository.findById(productId).get().apply { delete() })

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert · 삭제된 상품은 재고 0 과 같이 본다
            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }

        @DisplayName("남의 주문은 확정할 수 없고, 없는 주문과 같은 오류다 (P-02).")
        @Test
        fun hidesOthersOrder() {
            // arrange
            val otherUserId = userJpaRepository.save(UserFixture.user(loginId = "user2")).userId
            val productId = product()
            val orderId = orderService.create(
                userId = otherUserId,
                items = listOf(OrderFixture.item(productId = productId)),
                now = ZonedDateTime.now(),
            ).orderId

            // act & assert
            assertThat(assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_FOUND)
        }

        @DisplayName("이미 확정한 주문을 다시 확정하면, 재고가 두 번 빠지지 않는다 (P-29).")
        @Test
        fun rejectsSecondConfirm() {
            // arrange
            val productId = product(stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id
            orderFacade.confirm(loginId, orderId)

            // act
            val exception = assertThrows<CoreException> { orderFacade.confirm(loginId, orderId) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_NOT_DRAFT) },
                { assertThat(stockOf(productId)).isEqualTo(3) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(3_000L) },
            )
        }
    }

    @DisplayName("주문을 취소할 때,")
    @Nested
    inner class Cancel {
        @DisplayName("상태만 바뀐다. 차감한 것이 없어 되돌릴 것도 없다 (P-29).")
        @Test
        fun marksCanceledOnly() {
            // arrange
            val productId = product(stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(loginId, command(productId)).id

            // act
            val canceled = orderFacade.cancel(loginId, orderId)

            // assert
            assertAll(
                { assertThat(canceled.status).isEqualTo(OrderStatus.CANCELED) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(10_000L) },
            )
        }

        @DisplayName("확정된 주문은 취소할 수 없다 (P-29 · D-7).")
        @Test
        fun rejectsConfirmed() {
            // arrange
            val productId = product(stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = orderFacade.create(loginId, command(productId)).id
            orderFacade.confirm(loginId, orderId)

            // act & assert
            assertThat(assertThrows<CoreException> { orderFacade.cancel(loginId, orderId) }.errorType)
                .isEqualTo(ErrorType.ORDER_NOT_DRAFT)
        }
    }

    @DisplayName("내 주문을 조회할 때,")
    @Nested
    inner class Read {
        @DisplayName("상세는 품목까지 보인다 (C-11).")
        @Test
        fun showsItemsInDetail() {
            // arrange
            val productId = product()
            val orderId = orderFacade.create(loginId, command(productId, quantity = 2)).id

            // act
            val order = orderFacade.get(loginId, orderId)

            // assert
            assertAll(
                { assertThat(order.items).hasSize(1) },
                { assertThat(order.items.single().productId).isEqualTo(productId) },
                { assertThat(order.items.single().quantity).isEqualTo(2) },
            )
        }

        @DisplayName("목록은 최신 주문순이다 (P-46).")
        @Test
        fun listsLatestFirst() {
            // arrange
            val productId = product()
            val first = orderFacade.create(loginId, command(productId)).id
            val second = orderFacade.create(loginId, command(productId)).id

            // act
            val result = orderFacade.getOrders(loginId, PageCriteria(0, 20))

            // assert
            assertAll(
                { assertThat(result.items.map { it.id }).containsExactly(second, first) },
                { assertThat(result.totalCount).isEqualTo(2L) },
            )
        }
    }
}
