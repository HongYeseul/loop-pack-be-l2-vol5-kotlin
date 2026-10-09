package com.loopers.application.order

import com.loopers.domain.order.OrderService
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.point.PointTransactionRepository
import com.loopers.domain.point.PointTransactionType
import com.loopers.domain.user.LoginId
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.OrderFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.point.PointJpaRepository
import com.loopers.infrastructure.point.PointTransactionJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import jakarta.persistence.EntityManager
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
import java.sql.Connection
import java.sql.ResultSet
import java.time.ZonedDateTime
import javax.sql.DataSource

/**
 * 확정이 재고·잔액·원장·주문 상태를 한 덩어리로 반영하거나 되돌리는지 확인
 * 준비 데이터를 커밋해 두고, 트랜잭션이 끝난 뒤 새로 읽어 DB 에 남은 상태로 판단
 */
@SpringBootTest
class OrderTransactionTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val orderService: OrderService,
    private val pointService: PointService,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val pointJpaRepository: PointJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val entityManager: EntityManager,
    private val dataSource: DataSource,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @SpykBean
    private lateinit var pointTransactionRepository: PointTransactionRepository

    private val loginId = LoginId(UserFixture.DEFAULT_LOGIN_ID)
    private var userId: Long = 0L
    private var brandId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = userJpaRepository.save(UserFixture.user()).userId
        brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        pointService.charge(userId, INITIAL_BALANCE)
    }

    /** @Nested 에서는 스파이 설정이 다음 테스트로 새므로 원래 동작으로 직접 복원 */
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        every { pointTransactionRepository.save(any()) } answers { callOriginal() }
    }

    private data class Purchase(val productId: Long, val quantity: Int, val unitPrice: Long)

    private fun draftOrderOf(vararg purchases: Pair<Int, Long>): Pair<Long, List<Purchase>> {
        val bought = purchases.map { (quantity, price) ->
            val productId = productJpaRepository.save(
                ProductFixture.product(brandId = brandId, price = price, stock = INITIAL_STOCK),
            ).productId
            Purchase(productId, quantity, price)
        }
        val orderId = orderService.create(
            userId = userId,
            lines = bought.map { OrderFixture.line(productId = it.productId, quantity = it.quantity, unitPrice = it.unitPrice) },
            now = ZonedDateTime.now(),
        ).orderId
        return orderId to bought
    }

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock

    private fun balance(): Long = pointJpaRepository.findByUserId(userId)!!.balance

    private fun usedLedgerRows() = pointTransactionJpaRepository.findAll().filter { it.type == PointTransactionType.USE }

    /** 커밋 전 변경을 볼 수 있는 별도 연결로 읽어, 변경 SQL 이 DB 로 나갔는지 확인 */
    private fun <T> readUncommitted(sql: String, read: (ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_UNCOMMITTED
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    read(rows)
                }
            }
        }

    @DisplayName("여러 품목의 DRAFT 주문을 확정하면,")
    @Nested
    inner class WhenSucceeds {
        @DisplayName("재고·잔액·원장·주문 상태가 모두 반영된다.")
        @Test
        fun appliesEverything() {
            // arrange
            val (orderId, purchases) = draftOrderOf(2 to 3_500L, 1 to 1_000L, 3 to 500L)
            val total = purchases.sumOf { it.quantity * it.unitPrice }

            // act
            val result = orderFacade.confirm(loginId, orderId)

            // assert
            val order = orderJpaRepository.findById(orderId).get()
            val ledger = usedLedgerRows()
            assertAll(
                { assertThat(result.status).isEqualTo(OrderStatus.CONFIRMED) },
                { assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED) },
                { assertThat(order.paidAmount).isEqualTo(total) },
                { assertThat(order.confirmedAt).isNotNull() },
                { purchases.forEach { assertThat(stockOf(it.productId)).isEqualTo(INITIAL_STOCK - it.quantity) } },
                { assertThat(balance()).isEqualTo(INITIAL_BALANCE - total) },
                { assertThat(ledger).hasSize(1) },
                { assertThat(ledger.first().orderId).isEqualTo(orderId) },
                { assertThat(ledger.first().amount).isEqualTo(total) },
                { assertThat(ledger.first().balanceAfter).isEqualTo(INITIAL_BALANCE - total) },
            )
        }
    }

    @DisplayName("재고 차감과 잔액 차감이 DB 로 나간 뒤 원장 저장 단계에서 실패하면,")
    @Nested
    inner class WhenFailsAfterChangesAreSent {
        @DisplayName("주문은 DRAFT 로, 재고와 잔액은 처음 값으로 돌아가고 원장 행도 남지 않는다.")
        @Test
        fun rollsBackEverything() {
            // arrange
            val (orderId, purchases) = draftOrderOf(2 to 3_500L, 1 to 1_000L)
            var stocksSeenBeforeFailure = emptyList<Int>()
            var balanceSeenBeforeFailure = -1L
            var ledgerRowsSeenBeforeFailure = -1
            every { pointTransactionRepository.save(any()) } answers {
                callOriginal()
                entityManager.flush()
                stocksSeenBeforeFailure = purchases.map { purchase ->
                    readUncommitted("select stock from product where id = ${purchase.productId}") { it.getInt(1) }
                }
                balanceSeenBeforeFailure = readUncommitted("select balance from point where user_id = $userId") { it.getLong(1) }
                ledgerRowsSeenBeforeFailure = readUncommitted("select count(*) from point_transaction where type = 'USE'") { it.getInt(1) }
                throw IllegalStateException("원장 저장 뒤 실패")
            }

            // act
            assertThrows<IllegalStateException> { orderFacade.confirm(loginId, orderId) }

            // assert · 실패 시점에 변경이 이미 DB 로 나가 있었음
            val total = purchases.sumOf { it.quantity * it.unitPrice }
            assertAll(
                { assertThat(stocksSeenBeforeFailure).isEqualTo(purchases.map { INITIAL_STOCK - it.quantity }) },
                { assertThat(balanceSeenBeforeFailure).isEqualTo(INITIAL_BALANCE - total) },
                { assertThat(ledgerRowsSeenBeforeFailure).isEqualTo(1) },
            )

            // assert · 트랜잭션 종료 뒤 새로 읽은 값은 처음 상태
            val order = orderJpaRepository.findById(orderId).get()
            assertAll(
                { assertThat(order.status).isEqualTo(OrderStatus.DRAFT) },
                { assertThat(order.paidAmount).isNull() },
                { assertThat(order.confirmedAt).isNull() },
                { purchases.forEach { assertThat(stockOf(it.productId)).isEqualTo(INITIAL_STOCK) } },
                { assertThat(balance()).isEqualTo(INITIAL_BALANCE) },
                { assertThat(usedLedgerRows()).isEmpty() },
            )
        }
    }

    companion object {
        private const val INITIAL_STOCK = 10
        private const val INITIAL_BALANCE = 20_000L
    }
}
