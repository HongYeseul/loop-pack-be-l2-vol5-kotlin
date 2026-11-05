package com.loopers.payment.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.fixture.TestPaymentProvider
import com.loopers.payment.domain.ExternalPaymentStatus
import com.loopers.payment.domain.PaymentCreation
import com.loopers.payment.domain.PaymentListLookup
import com.loopers.payment.domain.PaymentLookup
import com.loopers.payment.domain.PaymentRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Duration

class PaymentProviderContractTest {
    private val provider = TestPaymentProvider()
    private val mapper = ObjectMapper()
    private val gateway = PgPaymentGateway(
        objectMapper = mapper,
        baseUrl = provider.baseUrl,
        callbackUrl = CALLBACK_URL,
        userId = "135135",
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = Duration.ofMillis(500),
    )

    @AfterEach
    fun tearDown() = provider.close()

    private fun request(orderId: String = "LP-ORD-0000000042", amount: Long = 7_000L) =
        PaymentRequest(providerOrderId = orderId, cardType = "TEST", cardNo = "0000-0000-0000-0000", amount = amount)

    @DisplayName("생성 요청은 고정 헤더와 JSON 타입을 지키고, 202 PENDING 을 생성 수락으로 매핑한다.")
    @Test
    fun sendsCreateRequestAndMapsAccepted() {
        // act
        val creation = gateway.create(request())

        // assert
        val sent = provider.requests.single()
        val body = mapper.readTree(sent.body)
        assertAll(
            { assertThat(sent.method).isEqualTo("POST") },
            { assertThat(sent.path).isEqualTo("/api/v1/payments") },
            { assertThat(sent.userId).isEqualTo("135135") },
            { assertThat(sent.contentType).startsWith("application/json") },
            { assertThat(body.path("orderId").isTextual).isTrue() },
            { assertThat(body.path("amount").isIntegralNumber).isTrue() },
            { assertThat(body.path("amount").longValue()).isEqualTo(7_000L) },
            { assertThat(body.path("callbackUrl").asText()).isEqualTo(CALLBACK_URL) },
            { assertThat((creation as PaymentCreation.Accepted).transaction.status).isEqualTo(ExternalPaymentStatus.PENDING) },
        )
    }

    @DisplayName("코드·불리언·주문·금액이 맞는 422 만 효과 없는 거절이다.")
    @Test
    fun mapsOnlyProvenRejectionToNoEffect() {
        // arrange
        provider.createMode = TestPaymentProvider.CreateMode.REJECT_BEFORE_CREATE

        // act
        val creation = gateway.create(request())

        // assert
        assertAll(
            { assertThat(creation).isEqualTo(PaymentCreation.RejectedNoEffect) },
            { assertThat(provider.transactions()).isEmpty() },
        )
    }

    @DisplayName("본문 없는 422·5xx 는 효과 0건의 증거가 아니라 결과 미확인이다.")
    @Test
    fun mapsUnprovenFailuresToUnknown() {
        // arrange · act
        provider.createMode = TestPaymentProvider.CreateMode.REJECT_WITHOUT_BODY
        val withoutBody = gateway.create(request())
        provider.createMode = TestPaymentProvider.CreateMode.SERVER_ERROR
        val serverError = gateway.create(request())

        // assert
        assertAll(
            { assertThat(withoutBody).isInstanceOf(PaymentCreation.Unknown::class.java) },
            { assertThat(serverError).isInstanceOf(PaymentCreation.Unknown::class.java) },
        )
    }

    @DisplayName("거래 생성 뒤 응답이 늦으면 결과 미확인이고, 숨은 재전송이 없다.")
    @Test
    fun timesOutWithoutResending() {
        // arrange
        provider.createMode = TestPaymentProvider.CreateMode.ACCEPT_THEN_DELAY
        provider.delay = Duration.ofSeconds(2)

        // act
        val creation = gateway.create(request())

        // assert
        assertAll(
            { assertThat(creation).isInstanceOf(PaymentCreation.Unknown::class.java) },
            { assertThat(provider.transactions()).hasSize(1) },
            { assertThat(provider.createCount.get()).isEqualTo(1) },
        )
    }

    @DisplayName("거래 조회는 200 을 거래로, 404 를 없음으로, 5xx 를 확인 불가로 매핑한다.")
    @Test
    fun mapsTransactionLookup() {
        // arrange
        val created = (gateway.create(request()) as PaymentCreation.Accepted).transaction
        provider.complete(created.transactionKey, "SUCCESS")

        // act
        val found = gateway.find(created.transactionKey)
        val missing = gateway.find("TEST-TX-9999")
        provider.lookupFails = true
        val unavailable = gateway.find(created.transactionKey)

        // assert
        assertAll(
            { assertThat((found as PaymentLookup.Found).transaction.status).isEqualTo(ExternalPaymentStatus.SUCCESS) },
            { assertThat(missing).isEqualTo(PaymentLookup.NotFound) },
            { assertThat(unavailable).isInstanceOf(PaymentLookup.Unavailable::class.java) },
        )
    }

    @DisplayName("주문별 조회는 query 를 인코딩하고, 빈 목록도 목록으로 돌려준다.")
    @Test
    fun encodesOrderQueryAndMapsEmptyList() {
        // act
        val listed = gateway.findByOrder("LP-ORD 1&x")

        // assert
        assertAll(
            { assertThat(provider.requests.single().rawQuery).isEqualTo("orderId=LP-ORD%201%26x") },
            { assertThat((listed as PaymentListLookup.Listed).transactions).isEmpty() },
        )
    }

    companion object {
        private const val CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback"
    }
}
