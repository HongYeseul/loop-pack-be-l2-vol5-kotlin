package com.loopers.application.order

import com.loopers.application.point.PointFacade
import com.loopers.domain.order.OrderService
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.point.PointTransactionType
import com.loopers.domain.user.LoginId
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.OrderFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.order.OrderItemJpaRepository
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.point.PointJpaRepository
import com.loopers.infrastructure.point.PointTransactionJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.support.ConcurrentRunner
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.ZonedDateTime

/**
 * 실제 서비스를 동시에 호출해 재고·잔액 불변식을 확인
 * 요청마다 독립된 트랜잭션으로 application·repository·MySQL 을 통과하고, 모든 worker 가 끝난 뒤 DB 를 다시 읽음
 */
@SpringBootTest
class OrderConcurrencyTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val pointFacade: PointFacade,
    private val orderService: OrderService,
    private val pointService: PointService,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val orderItemJpaRepository: OrderItemJpaRepository,
    private val pointJpaRepository: PointJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private var brandId: Long = 0L

    @BeforeEach
    fun setUp() {
        brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private data class Buyer(val loginId: LoginId, val userId: Long)

    private fun buyer(name: String, balance: Long): Buyer {
        val user = userJpaRepository.save(UserFixture.user(loginId = name))
        pointService.charge(user.userId, balance)
        return Buyer(LoginId(name), user.userId)
    }

    private fun product(price: Long, stock: Int): Long =
        productJpaRepository.save(ProductFixture.product(brandId = brandId, price = price, stock = stock)).productId

    private fun draftOrder(buyer: Buyer, productId: Long, price: Long, quantity: Int = 1): Long =
        orderService.create(
            userId = buyer.userId,
            lines = listOf(OrderFixture.line(productId = productId, quantity = quantity, unitPrice = price)),
            now = ZonedDateTime.now(),
        ).orderId

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock

    private fun balanceOf(userId: Long): Long = pointJpaRepository.findByUserId(userId)!!.balance

    private fun confirmedOrderIds(): List<Long> =
        orderJpaRepository.findAll().filter { it.status == OrderStatus.CONFIRMED }.map { it.orderId }

    private fun businessRejections(results: List<Result<*>>, type: ErrorType): Int =
        results.count { (it.exceptionOrNull() as? CoreException)?.errorType == type }

    /** 기대한 업무 거절이 아닌 모든 실패, 낙관적 충돌·교착·시간 초과 포함 */
    private fun technicalErrors(results: List<Result<*>>, expectedRejection: ErrorType): List<Throwable> =
        results.mapNotNull { it.exceptionOrNull() }
            .filterNot { (it as? CoreException)?.errorType == expectedRejection }

    @DisplayName("재고 5 인 상품을 서로 다른 주문 8개가 1개씩 동시에 확정하면,")
    @Nested
    inner class StockRace {
        @DisplayName("5건만 확정되고 3건은 재고 부족으로 거절되며, 기술 오류 없이 재고는 0 이다.")
        @Test
        fun confirmsOnlyAsMuchAsStock() {
            // arrange
            val productId = product(price = PRICE, stock = 5)
            val buyers = (1..8).map { buyer("buyer$it", balance = BALANCE) }
            val orderIds = buyers.map { draftOrder(it, productId, PRICE) }

            // act
            val results = ConcurrentRunner.run(
                buyers.zip(orderIds).map { (buyer, orderId) -> { orderFacade.confirm(buyer.loginId, orderId) } },
            )

            // assert · 요청 결과
            val success = results.count { it.isSuccess }
            val rejected = businessRejections(results, ErrorType.OUT_OF_STOCK)
            val technical = technicalErrors(results, ErrorType.OUT_OF_STOCK)
            assertAll(
                { assertThat(success).isEqualTo(5) },
                { assertThat(rejected).isEqualTo(3) },
                { assertThat(technical).isEmpty() },
                { assertThat(success + rejected + technical.size).isEqualTo(8) },
            )

            // assert · DB 상태와 식, 성공한 요청과 확정된 주문이 같아야 모든 요청을 거절한 코드를 걸러냄
            val confirmed = confirmedOrderIds()
            val soldQuantity = confirmed.sumOf { orderItemJpaRepository.findAllByOrderIdOrderByIdAsc(it).sumOf { item -> item.quantity } }
            val ledger = pointTransactionJpaRepository.findAll().filter { it.type == PointTransactionType.USE }
            assertAll(
                { assertThat(confirmed).hasSize(success) },
                { assertThat(5 - soldQuantity).isEqualTo(stockOf(productId)) },
                { assertThat(stockOf(productId)).isEqualTo(0) },
                { assertThat(ledger).hasSize(success) },
                { assertThat(buyers.sumOf { balanceOf(it.userId) }).isEqualTo(8 * BALANCE - success * PRICE) },
            )
        }
    }

    @DisplayName("잔액 10,000원인 한 사용자가 4,000원짜리 주문 3개를 동시에 확정하면,")
    @Nested
    inner class PointRace {
        @DisplayName("2건만 확정되고 1건은 잔액 부족으로 거절되며, 기술 오류 없이 잔액은 2,000원이다.")
        @Test
        fun confirmsOnlyAsMuchAsBalance() {
            // arrange
            val buyer = buyer("buyer1", balance = 10_000L)
            val productIds = List(3) { product(price = 4_000L, stock = 10) }
            val orderIds = productIds.map { draftOrder(buyer, it, 4_000L) }

            // act
            val results = ConcurrentRunner.run(
                orderIds.map { orderId -> { orderFacade.confirm(buyer.loginId, orderId) } },
            )

            // assert · 요청 결과
            val success = results.count { it.isSuccess }
            val rejected = businessRejections(results, ErrorType.INSUFFICIENT_BALANCE)
            val technical = technicalErrors(results, ErrorType.INSUFFICIENT_BALANCE)
            assertAll(
                { assertThat(success).isEqualTo(2) },
                { assertThat(rejected).isEqualTo(1) },
                { assertThat(technical).isEmpty() },
                { assertThat(success + rejected + technical.size).isEqualTo(3) },
            )

            // assert · DB 상태와 식, 거절된 주문의 재고는 그대로
            val confirmed = confirmedOrderIds()
            val confirmedProductIds = confirmed.flatMap { orderItemJpaRepository.findAllByOrderIdOrderByIdAsc(it).map { item -> item.productId } }
            val ledger = pointTransactionJpaRepository.findAll().filter { it.type == PointTransactionType.USE }
            assertAll(
                { assertThat(confirmed).hasSize(success) },
                { assertThat(balanceOf(buyer.userId)).isEqualTo(2_000L) },
                { assertThat(ledger).hasSize(success) },
                { productIds.forEach { assertThat(stockOf(it)).isEqualTo(if (it in confirmedProductIds) 9 else 10) } },
            )
        }
    }

    @DisplayName("잔액 10,000원에서 2,000원 충전과 7,000원 주문 확정을 동시에 실행하면,")
    @Nested
    inner class ChargeAndPay {
        @DisplayName("두 요청 모두 성공하고 기술 오류 없이 잔액은 5,000원이다.")
        @Test
        fun keepsBothChanges() {
            // arrange
            val buyer = buyer("buyer1", balance = 10_000L)
            val productId = product(price = 7_000L, stock = 10)
            val orderId = draftOrder(buyer, productId, 7_000L)

            // act
            val results = ConcurrentRunner.run(
                listOf<() -> Any>(
                    { pointFacade.charge(buyer.loginId, 2_000L) },
                    { orderFacade.confirm(buyer.loginId, orderId) },
                ),
            )

            // assert
            val ledger = pointTransactionJpaRepository.findAll()
            val charged = ledger.filter { it.type == PointTransactionType.CHARGE }.sumOf { it.amount }
            val used = ledger.filter { it.type == PointTransactionType.USE }.sumOf { it.amount }
            assertAll(
                { assertThat(results.mapNotNull { it.exceptionOrNull() }).isEmpty() },
                { assertThat(balanceOf(buyer.userId)).isEqualTo(5_000L) },
                { assertThat(charged - used).isEqualTo(balanceOf(buyer.userId)) },
                { assertThat(confirmedOrderIds()).containsExactly(orderId) },
            )
        }
    }

    companion object {
        private const val PRICE = 1_000L
        private const val BALANCE = 10_000L
    }
}
