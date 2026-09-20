package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.domain.order.OrderService
import com.loopers.domain.point.PointService
import com.loopers.domain.product.ProductStatus
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.OrderFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.order.OrderJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
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
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.ZonedDateTime

/**
 * C-9 생성 · C-10 확정 · C-11 조회 · C-12 취소.
 *
 * **응답과 저장을 함께 본다.** 확정 거절은 "409 로 답했다" 가 아니라 **"409 이면서 재고가 그대로"** 다
 * (P-27 · 설계 6-4절).
 *
 * 응답을 [JsonNode] 로 받는 이유는 **없어야 할 키의 부재**를 확인하기 위해서다 (DS-5) —
 * 목록 응답에는 품목이 없다 (설계 6-2절 C-11).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val orderService: OrderService,
    private val pointService: PointService,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ORDERS = "/api/v1/orders"
        private val CONFIRM: (Any) -> String = { orderId -> "$ORDERS/$orderId/confirm" }
        private val CANCEL: (Any) -> String = { orderId -> "$ORDERS/$orderId/cancel" }
        private val DETAIL: (Any) -> String = { orderId -> "$ORDERS/$orderId" }
        private const val OTHER_LOGIN_ID = "user2"
    }

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

    private fun headers(loginId: String?) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        loginId?.let { set("X-USER-ID", it) }
    }

    private fun post(url: String, body: String = "", loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        testRestTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers(loginId)), JsonNode::class.java)

    private fun get(url: String, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        testRestTemplate.exchange(url, HttpMethod.GET, HttpEntity<Any>(headers(loginId)), JsonNode::class.java)

    private fun createOrder(productId: Long, quantity: Int = 2, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        post(ORDERS, """{"items": [{"productId": $productId, "quantity": $quantity}]}""", loginId)

    private fun product(price: Long = 3_500L, stock: Int = 5, status: ProductStatus = ProductStatus.ON_SALE): Long =
        productJpaRepository.save(ProductFixture.product(brandId = brandId, price = price, stock = stock, status = status)).productId

    private fun draft(productId: Long, quantity: Int = 2): Long =
        createOrder(productId, quantity).body.orderId()!!

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock

    private fun JsonNode?.data(): JsonNode? = this?.path("data")

    private fun JsonNode?.orderId(): Long? = this.data()?.path("id")?.asLong()

    private fun JsonNode?.status(): String? = this.data()?.path("status")?.asText()

    private fun JsonNode?.errorCode(): String? = this?.path("meta")?.path("errorCode")?.asText()

    @DisplayName("POST /api/v1/orders")
    @Nested
    inner class Create {
        @DisplayName("주문을 만들면 201 로 답하고, 품목과 합계가 함께 나온다 (C-9 · P-23).")
        @Test
        fun createsDraft() {
            // arrange
            val productId = product(price = 3_500L)

            // act
            val response = createOrder(productId, quantity = 2)

            // assert · 재고는 줄지 않는다 (D-13)
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED) },
                { assertThat(response.body.status()).isEqualTo("DRAFT") },
                { assertThat(response.body.data()?.path("totalAmount")?.asLong()).isEqualTo(7_000L) },
                // 봉투가 null 필드를 싣지 않는다 — 아직 결제하지 않은 주문에는 키 자체가 없다
                { assertThat(response.body.data()?.has("paidAmount")).isFalse() },
                { assertThat(response.body.data()?.path("items")).hasSize(1) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
            )
        }

        /** 요청에 단가가 있어도 서버가 쓰지 않는다 — 단가는 상품에서 복사한다 (P-28). */
        @DisplayName("요청이 보낸 단가는 무시하고, 상품의 가격을 쓴다 (P-28).")
        @Test
        fun copiesUnitPriceFromProduct() {
            // arrange
            val productId = product(price = 3_500L)

            // act
            val response = post(ORDERS, """{"items": [{"productId": $productId, "quantity": 1, "unitPrice": 1}]}""")

            // assert
            assertThat(response.body.data()?.path("items")?.first()?.path("unitPrice")?.asLong()).isEqualTo(3_500L)
        }

        /** 설계 6-4절 기대값 — `[{1, 2}, {1, 3}]` */
        @DisplayName("같은 상품이 두 품목이면 400 · DUPLICATE_ORDER_ITEM (P-25).")
        @Test
        fun rejectsDuplicateItem() {
            // arrange
            val productId = product()

            // act
            val response = post(
                ORDERS,
                """{"items": [{"productId": $productId, "quantity": 2}, {"productId": $productId, "quantity": 3}]}""",
            )

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.DUPLICATE_ORDER_ITEM.code) },
                { assertThat(orderJpaRepository.count()).isZero() },
            )
        }

        @DisplayName("수량이 0 이하면 400 · INVALID_QUANTITY (P-24).")
        @Test
        fun rejectsNonPositiveQuantity() {
            // act
            val response = createOrder(product(), quantity = 0)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.INVALID_QUANTITY.code) },
            )
        }

        /** 형식은 `interfaces`, 규칙은 `domain` 이다 (DS-2). 요청자가 고칠 대상이 다르다. */
        @DisplayName("수량이 숫자가 아니면 형식 오류다. 수량 0 과 식별자가 다르다 (DS-2).")
        @Test
        fun rejectsNonNumericQuantityAsFormatError() {
            // act
            val response = post(ORDERS, """{"items": [{"productId": ${product()}, "quantity": "두 개"}]}""")

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isNotEqualTo(ErrorType.INVALID_QUANTITY.code) },
            )
        }

        @DisplayName("없는 상품이면 404 · PRODUCT_NOT_FOUND (P-24).")
        @Test
        fun rejectsMissingProduct() {
            // act
            val response = createOrder(productId = 999L)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND.code) },
            )
        }

        /** 설계 6-4절 기대값 — 단종 상품으로 주문 생성 */
        @DisplayName("단종된 상품이면 409 · PRODUCT_NOT_PURCHASABLE (P-38).")
        @Test
        fun rejectsDiscontinuedProduct() {
            // act
            val response = createOrder(product(status = ProductStatus.DISCONTINUED))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.PRODUCT_NOT_PURCHASABLE.code) },
            )
        }

        @DisplayName("헤더가 없으면 400 · USER_NOT_IDENTIFIED (P-01).")
        @Test
        fun rejectsMissingHeader() {
            // act
            val response = createOrder(product(), loginId = null)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_IDENTIFIED.code) },
            )
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    inner class Confirm {
        @DisplayName("확정하면 200 · 상태와 결제액과 잔액이 나온다 (C-10).")
        @Test
        fun confirmsOrder() {
            // arrange
            val productId = product(price = 3_500L, stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = draft(productId, quantity = 2)

            // act
            val response = post(CONFIRM(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.status()).isEqualTo("CONFIRMED") },
                { assertThat(response.body.data()?.path("paidAmount")?.asLong()).isEqualTo(7_000L) },
                { assertThat(response.body.data()?.path("balance")?.asLong()).isEqualTo(3_000L) },
                { assertThat(stockOf(productId)).isEqualTo(3) },
            )
        }

        /** 설계 6-4절 기대값 — 잔액 0 · 합계 0 */
        @DisplayName("합계 0원은 잔액 0이어도 확정되고, 재고는 차감된다 (P-30).")
        @Test
        fun confirmsZeroAmountOrder() {
            // arrange
            val productId = product(price = 0L, stock = 5)
            val orderId = draft(productId, quantity = 2)

            // act
            val response = post(CONFIRM(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.data()?.path("paidAmount")?.asLong()).isZero() },
                { assertThat(stockOf(productId)).isEqualTo(3) },
            )
        }

        @DisplayName("잔액이 부족하면 409 · INSUFFICIENT_BALANCE 이고 재고는 그대로다 (P-27).")
        @Test
        fun rejectsWhenBalanceIsNotEnough() {
            // arrange
            val productId = product(price = 3_500L, stock = 5)
            val orderId = draft(productId, quantity = 2)

            // act
            val response = post(CONFIRM(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.INSUFFICIENT_BALANCE.code) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
                { assertThat(orderJpaRepository.findById(orderId).get().status.name).isEqualTo("DRAFT") },
            )
        }

        /** 설계 6-4절 기대값 — 생성 10분 1초 뒤 확정 */
        @DisplayName("만료된 주문이면 409 · ORDER_EXPIRED 이고 재고·잔액이 그대로다 (P-32).")
        @Test
        fun rejectsExpiredOrder() {
            // arrange
            val productId = product(stock = 5)
            pointService.charge(userId, 10_000L)
            val orderId = orderService.create(
                userId = userId,
                items = listOf(OrderFixture.item(productId = productId)),
                now = ZonedDateTime.now().minusMinutes(10).minusSeconds(1),
            ).orderId

            // act
            val response = post(CONFIRM(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.ORDER_EXPIRED.code) },
                { assertThat(stockOf(productId)).isEqualTo(5) },
                { assertThat(pointService.getBalance(userId)).isEqualTo(10_000L) },
            )
        }

        @DisplayName("남의 주문이면 404 · ORDER_NOT_FOUND — 존재를 숨긴다 (P-02).")
        @Test
        fun hidesOthersOrder() {
            // arrange
            userJpaRepository.save(UserFixture.user(loginId = OTHER_LOGIN_ID))
            val orderId = draft(product())

            // act
            val response = post(CONFIRM(orderId), loginId = OTHER_LOGIN_ID)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.ORDER_NOT_FOUND.code) },
            )
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/cancel")
    @Nested
    inner class Cancel {
        @DisplayName("취소하면 200 · CANCELED 다 (C-12 · P-29).")
        @Test
        fun cancelsDraft() {
            // arrange
            val orderId = draft(product())

            // act
            val response = post(CANCEL(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.status()).isEqualTo("CANCELED") },
            )
        }

        @DisplayName("확정된 주문이면 409 · ORDER_NOT_DRAFT (P-29).")
        @Test
        fun rejectsConfirmedOrder() {
            // arrange
            val productId = product()
            pointService.charge(userId, 10_000L)
            val orderId = draft(productId)
            post(CONFIRM(orderId))

            // act
            val response = post(CANCEL(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.ORDER_NOT_DRAFT.code) },
            )
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    inner class Read {
        @DisplayName("목록은 최신 주문순이고 총 개수가 함께 나온다 (C-11 · P-46).")
        @Test
        fun listsLatestFirst() {
            // arrange
            val productId = product()
            val first = draft(productId)
            val second = draft(productId)

            // act
            val response = get(ORDERS)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.data()?.path("items")?.map { it.path("id").asLong() }).containsExactly(second, first) },
                { assertThat(response.body.data()?.path("totalCount")?.asLong()).isEqualTo(2L) },
            )
        }

        /** 목록은 요약이다 (설계 6-2절 C-11). 품목은 상세에서만 읽는다. */
        @DisplayName("목록 한 줄에는 품목이 없다 (DS-5).")
        @Test
        fun omitsItemsInList() {
            // arrange
            draft(product())

            // act
            val response = get(ORDERS)

            // assert
            assertThat(response.body.data()?.path("items")?.first()?.has("items")).isFalse()
        }

        @DisplayName("상세는 품목과 만료 시각까지 보인다 (C-11).")
        @Test
        fun showsDetail() {
            // arrange
            val productId = product()
            val orderId = draft(productId, quantity = 2)

            // act
            val response = get(DETAIL(orderId))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.data()?.path("items")?.first()?.path("productId")?.asLong()).isEqualTo(productId) },
                { assertThat(response.body.data()?.path("items")?.first()?.path("quantity")?.asInt()).isEqualTo(2) },
                { assertThat(response.body.data()?.path("expiresAt")?.isMissingNode).isFalse() },
            )
        }

        @DisplayName("남의 주문 상세는 404 · ORDER_NOT_FOUND (P-02).")
        @Test
        fun hidesOthersOrder() {
            // arrange
            userJpaRepository.save(UserFixture.user(loginId = OTHER_LOGIN_ID))
            val orderId = draft(product())

            // act
            val response = get(DETAIL(orderId), loginId = OTHER_LOGIN_ID)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.ORDER_NOT_FOUND.code) },
            )
        }

        @DisplayName("내 목록에 남의 주문은 섞이지 않는다 (P-02).")
        @Test
        fun excludesOthersOrders() {
            // arrange
            userJpaRepository.save(UserFixture.user(loginId = OTHER_LOGIN_ID))
            draft(product())

            // act
            val response = get(ORDERS, loginId = OTHER_LOGIN_ID)

            // assert
            assertThat(response.body.data()?.path("totalCount")?.asLong()).isZero()
        }
    }
}
