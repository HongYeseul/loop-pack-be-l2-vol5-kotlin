package com.loopers.purchase

import com.loopers.purchase.application.PurchaseActivityService
import com.loopers.purchase.domain.PurchaseContentConflictException
import com.loopers.purchase.infrastructure.PurchaseActivityJpaRepository
import com.loopers.testcontainers.MySqlTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaSystemException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["commerce.kafka.purchase-activity-enabled=false", "spring.jpa.show-sql=false"],
)
@Import(MySqlTestContainersConfig::class)
@DisplayName("MySQL 구매 활동 저장·중복 방지·트랜잭션 롤백")
class PurchaseActivityPersistenceTest @Autowired constructor(
    private val service: PurchaseActivityService,
    private val jpa: PurchaseActivityJpaRepository,
    private val jdbc: JdbcTemplate,
) {
    @BeforeEach
    fun clean() = jpa.deleteAllInBatch()

    @Test
    @DisplayName("처음 받은 구매의 전체 품목을 저장한다")
    fun savesAllItems() {
        val purchase = PurchaseEvents.purchase()

        service.record(purchase)

        assertThat(purchase.matches(jpa.findAllByOrderIdOrderByProductIdAsc(42))).isTrue()
        assertThat(jpa.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 구매를 반복해서 받으면 중복 저장하지 않는다")
    fun acceptsRepeatedDelivery() {
        val purchase = PurchaseEvents.purchase()
        service.record(purchase)

        service.record(purchase)

        assertThat(purchase.matches(jpa.findAllByOrderIdOrderByProductIdAsc(42))).isTrue()
        assertThat(jpa.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 구매의 품목 순서가 바뀌어도 중복 저장하지 않는다")
    fun acceptsReorderedDelivery() {
        val purchase = PurchaseEvents.purchase()
        service.record(purchase)

        service.record(purchase.copy(items = purchase.items.reversed()))

        assertThat(purchase.matches(jpa.findAllByOrderIdOrderByProductIdAsc(42))).isTrue()
        assertThat(jpa.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 구매를 동시에 저장해도 주문·상품별 구매 활동은 한 건만 남는다")
    fun concurrentIdenticalRequestsProduceOnePurchase() {
        concurrently { service.record(PurchaseEvents.purchase()) }
        assertThat(jpa.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 주문의 다른 수량을 동시에 저장하면 한 요청만 성공하고 다른 요청은 충돌로 거부한다")
    fun concurrentDifferentContentsHaveOneWinnerAndOneConflict() {
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val results = listOf(2, 3).map { quantity ->
                pool.submit(
                    Callable {
                    gate.await()
                    runCatching { service.record(PurchaseEvents.purchase(quantity = quantity)) }.exceptionOrNull()
                },
                )
            }
            gate.countDown()
            val errors = results.map { it.get(15, SECONDS) }
            assertThat(errors.count { it == null }).isEqualTo(1)
            assertThat(errors.filterNotNull().single()).isInstanceOf(PurchaseContentConflictException::class.java)
            assertThat(jpa.count()).isEqualTo(2)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @DisplayName("기존 구매와 수량이 다르면 충돌로 거부하고 기존 기록을 유지한다")
    fun rejectsDifferentContentsWithoutOverwriting() {
        val purchase = PurchaseEvents.purchase()
        service.record(purchase)

        assertThrows<PurchaseContentConflictException> { service.record(PurchaseEvents.purchase(quantity = 3)) }

        assertThat(purchase.matches(jpa.findAllByOrderIdOrderByProductIdAsc(42))).isTrue()
    }

    @Test
    @DisplayName("일부 품목만 저장돼 있으면 충돌로 거부하고 기존 기록을 유지한다")
    fun rejectsPartialContentsWithoutOverwriting() {
        service.record(PurchaseEvents.purchase())
        jdbc.update("DELETE FROM purchase_activity WHERE product_id = 8")

        assertThrows<PurchaseContentConflictException> { service.record(PurchaseEvents.purchase()) }

        assertThat(jpa.count()).isEqualTo(1)
        assertThat(jpa.findAll().single().quantity).isEqualTo(2)
    }

    @Test
    @DisplayName("두 번째 품목 저장에 실패하면 첫 번째 품목도 롤백한다")
    fun rollsBackFirstInsertWhenSecondItemFails() {
        jdbc.execute("ALTER TABLE purchase_activity ADD CONSTRAINT test_reject_product CHECK (product_id <> 8)")
        try {
            assertThrows<JpaSystemException> { service.record(PurchaseEvents.purchase()) }
            assertThat(jpa.count()).isZero()
        } finally {
            jdbc.execute("ALTER TABLE purchase_activity DROP CHECK test_reject_product")
        }
    }

    @Test
    @DisplayName("품목 저장 실패 원인을 제거한 뒤 재요청하면 전체 품목을 저장한다")
    fun savesAllItemsAfterFailedTransaction() {
        jdbc.execute("ALTER TABLE purchase_activity ADD CONSTRAINT test_reject_product CHECK (product_id <> 8)")
        try {
            assertThrows<JpaSystemException> { service.record(PurchaseEvents.purchase()) }
        } finally {
            jdbc.execute("ALTER TABLE purchase_activity DROP CHECK test_reject_product")
        }

        service.record(PurchaseEvents.purchase())

        assertThat(jpa.count()).isEqualTo(2)
    }

    private fun concurrently(action: () -> Unit) {
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val results = (1..2).map {
                pool.submit(
                    Callable {
                gate.await()
                action()
            },
                )
            }
            gate.countDown()
            results.forEach { it.get(15, SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
