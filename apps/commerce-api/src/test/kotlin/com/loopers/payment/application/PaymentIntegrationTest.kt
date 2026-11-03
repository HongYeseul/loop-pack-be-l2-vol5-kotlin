package com.loopers.payment.application

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.TestPaymentProvider
import com.loopers.fixture.UserFixture
import com.loopers.order.application.OrderCommand
import com.loopers.order.application.OrderFacade
import com.loopers.order.domain.OrderStatus
import com.loopers.order.domain.StockReservationStatus
import com.loopers.order.infrastructure.OrderJpaRepository
import com.loopers.order.infrastructure.StockReservationJpaRepository
import com.loopers.payment.domain.PaymentAttempt
import com.loopers.payment.infrastructure.PaymentAttemptJpaRepository
import com.loopers.point.domain.PointService
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.support.ConcurrentRunner
import com.loopers.user.domain.LoginId
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration

/**
 * 실제 MySQL·controller 와 테스트 PG 로 결제 결과를 한 번만 반영하는지 확인
 * 재고 5개, 수량 2개 주문 기준: 확보 뒤 3, 성공 뒤 3, 실패 반환 뒤 5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIntegrationTest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val orderFacade: OrderFacade,
    private val pointService: PointService,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val stockReservationJpaRepository: StockReservationJpaRepository,
    private val paymentAttemptJpaRepository: PaymentAttemptJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val INITIAL_POINT = 10_000L
        private val provider = TestPaymentProvider()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("commerce.pg.base-url") { provider.baseUrl }
            registry.add("commerce.pg.read-timeout") { "500ms" }
        }

        @JvmStatic
        @AfterAll
        fun closeProvider() = provider.close()
    }

    private val loginId = LoginId(UserFixture.DEFAULT_LOGIN_ID)
    private var userId = 0L
    private var productId = 0L

    @BeforeEach
    fun setUp() {
        userId = userJpaRepository.save(UserFixture.user()).userId
        val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        productId = productJpaRepository.save(ProductFixture.product(brandId = brandId, price = 3_500L, stock = 5)).productId
        pointService.charge(userId, INITIAL_POINT)
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        provider.reset()
    }

    private fun draft(quantity: Int = 2): Long =
        orderFacade.create(loginId, OrderCommand(items = listOf(OrderCommand.Item(productId = productId, quantity = quantity)))).id

    private fun headers() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-USER-ID", UserFixture.DEFAULT_LOGIN_ID)
    }

    private fun post(url: String, body: String = ""): ResponseEntity<JsonNode> =
        testRestTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers()), JsonNode::class.java)

    private fun start(orderId: Long, attemptKey: String = "attempt-$orderId") = post(
        "/api/v1/orders/$orderId/payment",
        """{"paymentAttemptKey": "$attemptKey", "cardType": "TEST", "cardNo": "0000-0000-0000-0000"}""",
    )

    private fun reconcile(orderId: Long) = post("/api/v1/orders/$orderId/payment/reconcile")

    private fun callback(orderId: Long, transactionKey: String, status: String) = post(
        "/api/v1/payments/callback",
        """{"orderId": "${PaymentAttempt.providerOrderIdOf(orderId)}", "transactionKey": "$transactionKey", "status": "$status"}""",
    )

    private fun stock(): Int = productJpaRepository.findById(productId).get().stock

    private fun orderStatus(orderId: Long): OrderStatus = orderJpaRepository.findById(orderId).get().status

    private fun reservation(orderId: Long): StockReservationStatus? = stockReservationJpaRepository.findByOrderId(orderId)?.status

    private fun onlyTransactionKey(orderId: Long): String =
        provider.transactionsOf(PaymentAttempt.providerOrderIdOf(orderId)).single().transactionKey

    private fun JsonNode?.paymentStatus(): String? = this?.path("data")?.path("paymentStatus")?.asText()

    private fun JsonNode?.errorCode(): String? = this?.path("meta")?.path("errorCode")?.asText()

    @DisplayName("생성 수락은 확정이 아니고, 권위 조회로 SUCCESS 를 본 뒤에만 추가 차감 없이 확정한다.")
    @Test
    fun confirmsOnlyAfterVerifiedSuccess() {
        // arrange
        val orderId = draft()

        // act · 생성 수락
        val started = start(orderId)

        // assert · 재고 확보, 아직 미확정
        assertAll(
            { assertThat(started.statusCode).isEqualTo(HttpStatus.ACCEPTED) },
            { assertThat(started.body.paymentStatus()).isEqualTo("PENDING") },
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING) },
            { assertThat(stock()).isEqualTo(3) },
        )

        // act · PG 성공 뒤 callback, 이어서 늦게 온 reconcile
        val transactionKey = onlyTransactionKey(orderId)
        provider.complete(transactionKey, "SUCCESS")
        callback(orderId, transactionKey, "SUCCESS")
        val reconciled = reconcile(orderId)

        // assert
        assertAll(
            { assertThat(reconciled.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(reconciled.body.paymentStatus()).isEqualTo("SUCCESS") },
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED) },
            { assertThat(reservation(orderId)).isEqualTo(StockReservationStatus.CONSUMED) },
            { assertThat(stock()).isEqualTo(3) },
            { assertThat(pointService.getBalance(userId)).isEqualTo(INITIAL_POINT) },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
            { assertThat(provider.transactions()).hasSize(1) },
        )
    }

    @DisplayName("callback 본문이 SUCCESS 여도 PG 조회가 PENDING 이면 확정하지 않는다.")
    @Test
    fun ignoresCallbackBodyStatus() {
        // arrange
        val orderId = draft()
        start(orderId)

        // act
        callback(orderId, onlyTransactionKey(orderId), "SUCCESS")

        // assert
        assertAll(
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING) },
            { assertThat(reservation(orderId)).isEqualTo(StockReservationStatus.RESERVED) },
            { assertThat(stock()).isEqualTo(3) },
        )
    }

    @DisplayName("효과 0건이 보장된 생성 거절은 결제 실패로 끝내고 재고를 한 번만 돌려준다.")
    @Test
    fun failsAndReleasesOnRejectionBeforeCreate() {
        // arrange
        val orderId = draft()
        provider.createMode = TestPaymentProvider.CreateMode.REJECT_BEFORE_CREATE

        // act · 거절 뒤 같은 시도 재요청과 reconcile
        val started = start(orderId)
        start(orderId)
        reconcile(orderId)

        // assert
        assertAll(
            { assertThat(started.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(started.body.paymentStatus()).isEqualTo("FAILED") },
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED) },
            { assertThat(reservation(orderId)).isEqualTo(StockReservationStatus.RELEASED) },
            { assertThat(stock()).isEqualTo(5) },
            { assertThat(provider.transactions()).isEmpty() },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
        )
    }

    @DisplayName("거래 생성 뒤 응답이 유실되면 UNKNOWN 으로 재고를 유지하고, 재발송 없이 기존 거래 조회로 종결한다.")
    @Test
    fun recoversLostCreateResponseByLookup() {
        // arrange
        val orderId = draft()
        provider.createMode = TestPaymentProvider.CreateMode.ACCEPT_THEN_DELAY
        provider.delay = Duration.ofMillis(1_500)

        // act · 응답 유실, 같은 시도 재요청
        val lost = start(orderId)
        val retried = start(orderId)

        // assert
        assertAll(
            { assertThat(lost.statusCode).isEqualTo(HttpStatus.ACCEPTED) },
            { assertThat(lost.body.paymentStatus()).isEqualTo("UNKNOWN") },
            { assertThat(retried.body.paymentStatus()).isEqualTo("UNKNOWN") },
            { assertThat(stock()).isEqualTo(3) },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
        )

        // act · 주문 번호로 기존 거래를 찾고, 성공 뒤 다시 확인
        val found = reconcile(orderId)
        provider.complete(onlyTransactionKey(orderId), "SUCCESS")
        val confirmed = reconcile(orderId)

        // assert
        assertAll(
            { assertThat(found.body.paymentStatus()).isEqualTo("PENDING") },
            { assertThat(confirmed.body.paymentStatus()).isEqualTo("SUCCESS") },
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.CONFIRMED) },
            { assertThat(stock()).isEqualTo(3) },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
            { assertThat(provider.transactions()).hasSize(1) },
        )
    }

    @DisplayName("같은 시도의 동시 요청은 시도·확보를 하나만 만들고 PG create 도 한 번만 보낸다.")
    @Test
    fun dispatchesOnceForConcurrentSameAttempt() {
        // arrange
        val orderId = draft()

        // act
        val results = ConcurrentRunner.run(List(4) { { start(orderId, attemptKey = "same-key") } })

        // assert
        assertAll(
            { assertThat(results).allMatch { it.getOrNull()?.statusCode?.is2xxSuccessful == true } },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
            { assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1) },
            { assertThat(stock()).isEqualTo(3) },
        )
    }

    @DisplayName("같은 주문에 다른 시도 키를 보내면 두 번째 PG 시도 없이 거절한다.")
    @Test
    fun rejectsSecondAttemptKeyForSameOrder() {
        // arrange
        val orderId = draft()
        start(orderId, attemptKey = "first")

        // act
        val second = start(orderId, attemptKey = "second")

        // assert
        assertAll(
            { assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT) },
            { assertThat(second.body.errorCode()).isEqualTo("PAYMENT_ATTEMPT_CONFLICT") },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
            { assertThat(stock()).isEqualTo(3) },
        )
    }

    @DisplayName("실패 알림이 중복으로 와도 재고는 한 번만 돌아간다.")
    @Test
    fun releasesOnceForDuplicateFailure() {
        // arrange
        val orderId = draft()
        start(orderId)
        val transactionKey = onlyTransactionKey(orderId)
        provider.complete(transactionKey, "FAILED")

        // act
        callback(orderId, transactionKey, "FAILED")
        callback(orderId, transactionKey, "FAILED")

        // assert
        assertAll(
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED) },
            { assertThat(reservation(orderId)).isEqualTo(StockReservationStatus.RELEASED) },
            { assertThat(stock()).isEqualTo(5) },
        )
    }

    @DisplayName("이전 포인트 확정 주문은 PG 를 부르지 않고 기존 확정 상태 오류를 준다.")
    @Test
    fun keepsPointConfirmedOrder() {
        // arrange
        val orderId = draft()
        orderFacade.confirm(loginId, orderId)

        // act
        val response = start(orderId)

        // assert
        assertAll(
            { assertThat(response.body.errorCode()).isEqualTo("ORDER_NOT_DRAFT") },
            { assertThat(provider.createCount.get()).isEqualTo(0) },
            { assertThat(pointService.getBalance(userId)).isEqualTo(INITIAL_POINT - 7_000L) },
        )
    }

    @DisplayName("재고가 부족하면 PG 를 부르지 않고 아무것도 저장하지 않는다.")
    @Test
    fun rejectsOutOfStockBeforeCallingProvider() {
        // arrange
        val orderId = draft(quantity = 6)

        // act
        val response = start(orderId)

        // assert
        assertAll(
            { assertThat(response.body.errorCode()).isEqualTo("OUT_OF_STOCK") },
            { assertThat(provider.createCount.get()).isEqualTo(0) },
            { assertThat(paymentAttemptJpaRepository.count()).isEqualTo(0) },
            { assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.DRAFT) },
            { assertThat(stock()).isEqualTo(5) },
        )
    }
}
