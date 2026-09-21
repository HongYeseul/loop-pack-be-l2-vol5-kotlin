package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
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

/**
 * 기획 S-3 · 설계 5절의 대표 흐름을 **HTTP 로만** 이어서 본다 (설계 9절 · 6-4절 마지막 줄).
 *
 * 기능별 E2E 는 한 API 안에서만 확인한다 — 충전은 `pointService` 로 미리 넣고 확정만 부르는 식이다.
 * 그러면 **앞 API 의 결과가 뒤 API 에 실제로 보이는지**는 아무도 보지 않는다. 이 클래스가 그 자리다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PurchaseFlowE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val CHARGE = "/api/v1/points/charge"
        private const val BALANCE = "/api/v1/points"
        private const val ORDERS = "/api/v1/orders"
    }

    private var brandId: Long = 0L

    @BeforeEach
    fun setUp() {
        userJpaRepository.save(UserFixture.user())
        brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun headers() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-USER-ID", UserFixture.DEFAULT_LOGIN_ID)
    }

    private fun post(url: String, body: String = "") =
        testRestTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers()), JsonNode::class.java)

    private fun get(url: String) =
        testRestTemplate.exchange(url, HttpMethod.GET, HttpEntity<Any>(headers()), JsonNode::class.java)

    private fun JsonNode?.data(): JsonNode? = this?.path("data")

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock

    @DisplayName("충전하고 주문하고 확정하면, 잔액 0 에서 10,000 을 채워 7,000 을 쓰고 3,000 이 남는다 (기획 S-3).")
    @Test
    fun chargesAndOrdersAndConfirms() {
        val productId = productJpaRepository.save(ProductFixture.product(brandId = brandId, price = 3_500L, stock = 5)).productId

        // 0 · 충전한 적이 없으면 잔액은 0 이다 (P-20)
        assertThat(get(BALANCE).body.data()?.path("balance")?.asLong()).isZero()

        // 1 · 10,000 충전 (C-7)
        val charged = post(CHARGE, """{"amount": 10000}""")
        assertAll(
            { assertThat(charged.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(charged.body.data()?.path("balance")?.asLong()).isEqualTo(10_000L) },
        )

        // 2 · 단가 3,500 짜리 2개로 DRAFT (C-9). 재고는 아직 5 다 (D-13)
        val created = post(ORDERS, """{"items": [{"productId": $productId, "quantity": 2}]}""")
        val orderId = created.body.data()?.path("id")?.asLong()
        assertAll(
            { assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED) },
            { assertThat(created.body.data()?.path("status")?.asText()).isEqualTo("DRAFT") },
            { assertThat(created.body.data()?.path("totalAmount")?.asLong()).isEqualTo(7_000L) },
            { assertThat(stockOf(productId)).isEqualTo(5) },
        )

        // 3 · 확정 (C-10). 여기서 잔액과 재고가 함께 준다 (P-26)
        val confirmed = post("$ORDERS/$orderId/confirm")
        assertAll(
            { assertThat(confirmed.statusCode).isEqualTo(HttpStatus.OK) },
            { assertThat(confirmed.body.data()?.path("status")?.asText()).isEqualTo("CONFIRMED") },
            { assertThat(confirmed.body.data()?.path("paidAmount")?.asLong()).isEqualTo(7_000L) },
            { assertThat(confirmed.body.data()?.path("balance")?.asLong()).isEqualTo(3_000L) },
            { assertThat(stockOf(productId)).isEqualTo(3) },
        )

        // 4 · 다시 조회해도 같다 (C-8 · C-11). 확정 응답만 믿으면 저장까지 갔는지는 모른다
        assertAll(
            { assertThat(get(BALANCE).body.data()?.path("balance")?.asLong()).isEqualTo(3_000L) },
            { assertThat(get("$ORDERS/$orderId").body.data()?.path("status")?.asText()).isEqualTo("CONFIRMED") },
            { assertThat(get("$ORDERS/$orderId").body.data()?.path("paidAmount")?.asLong()).isEqualTo(7_000L) },
        )
    }
}
