package com.loopers.payment.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.payment.domain.ExternalPaymentStatus
import com.loopers.payment.domain.PaymentCreation
import com.loopers.payment.domain.PaymentGateway
import com.loopers.payment.domain.PaymentListLookup
import com.loopers.payment.domain.PaymentLookup
import com.loopers.payment.domain.PaymentRequest
import com.loopers.payment.domain.PaymentTransaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/** 로컬 테스트 PG 계약의 HTTP adapter */
@Component
class PgPaymentGateway(
    private val objectMapper: ObjectMapper,
    @Value("\${commerce.pg.base-url}") baseUrl: String,
    @Value("\${commerce.pg.callback-url}") private val callbackUrl: String,
    @Value("\${commerce.pg.user-id}") userId: String,
    @Value("\${commerce.pg.connect-timeout:1s}") connectTimeout: Duration,
    @Value("\${commerce.pg.read-timeout:3s}") readTimeout: Duration,
) : PaymentGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    // JDK HttpClient 는 POST 를 자동 재전송하지 않음 (HttpURLConnection 은 기본 재전송)
    private val client = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build())
                .apply { setReadTimeout(readTimeout) },
        )
        .defaultHeader(USER_HEADER, userId)
        .build()

    override fun create(request: PaymentRequest): PaymentCreation =
        try {
            client.post()
                .uri(PAYMENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "orderId" to request.providerOrderId,
                        "cardType" to request.cardType,
                        "cardNo" to request.cardNo,
                        "amount" to request.amount,
                        "callbackUrl" to callbackUrl,
                    ),
                )
                .exchange { _, response ->
                    readCreation(request, response.statusCode.value(), response.body.readAllBytes().decodeToString())
                }
        } catch (error: Exception) {
            log.warn("PG 결제 생성 결과 미확인: providerOrderId={}, cause={}", request.providerOrderId, error.javaClass.simpleName)
            PaymentCreation.Unknown(error.javaClass.simpleName)
        }

    override fun find(transactionKey: String): PaymentLookup =
        try {
            client.get()
                .uri("$PAYMENTS/{transactionKey}", transactionKey)
                .exchange { _, response ->
                    when (val status = response.statusCode.value()) {
                        200 -> parse(readTree(response.body.readAllBytes().decodeToString()))
                            ?.let { PaymentLookup.Found(it) }
                            ?: PaymentLookup.Unavailable("잘못된 거래 응답")
                        404 -> PaymentLookup.NotFound
                        else -> PaymentLookup.Unavailable("HTTP $status")
                    }
                }
        } catch (error: Exception) {
            PaymentLookup.Unavailable(error.javaClass.simpleName)
        }

    override fun findByOrder(providerOrderId: String): PaymentListLookup =
        try {
            client.get()
                .uri { it.path(PAYMENTS).queryParam("orderId", providerOrderId).build() }
                .exchange { _, response ->
                    val status = response.statusCode.value()
                    if (status != 200) return@exchange PaymentListLookup.Unavailable("HTTP $status")
                    val node = readTree(response.body.readAllBytes().decodeToString())
                    if (node == null || !node.isArray) return@exchange PaymentListLookup.Unavailable("잘못된 목록 응답")
                    val transactions = node.map { parse(it) }
                    if (transactions.any { it == null }) {
                        PaymentListLookup.Unavailable("잘못된 거래 응답")
                    } else {
                        PaymentListLookup.Listed(transactions.filterNotNull())
                    }
                }
        } catch (error: Exception) {
            PaymentListLookup.Unavailable(error.javaClass.simpleName)
        }

    private fun readCreation(request: PaymentRequest, status: Int, body: String): PaymentCreation {
        val node = readTree(body)
        return when (status) {
            202 -> parse(node)
                ?.takeIf { it.providerOrderId == request.providerOrderId && it.amount == request.amount }
                ?.let { PaymentCreation.Accepted(it) }
                ?: PaymentCreation.Unknown("잘못된 생성 응답")
            422 -> if (isRejectedBeforeCreate(node, request)) {
                PaymentCreation.RejectedNoEffect
            } else {
                PaymentCreation.Unknown("효과 없음이 확인되지 않은 422")
            }
            else -> PaymentCreation.Unknown("HTTP $status")
        }
    }

    /** 코드·불리언·주문·금액이 모두 맞아야 거래 0건의 증거 */
    private fun isRejectedBeforeCreate(node: JsonNode?, request: PaymentRequest): Boolean =
        node != null &&
            node.path("code").isTextual && node.path("code").asText() == REJECTED_BEFORE_CREATE &&
            node.path("transactionCreated").isBoolean && !node.path("transactionCreated").booleanValue() &&
            node.path("orderId").isTextual && node.path("orderId").asText() == request.providerOrderId &&
            node.path("amount").isIntegralNumber && node.path("amount").longValue() == request.amount

    private fun parse(node: JsonNode?): PaymentTransaction? {
        if (node == null) return null
        val transactionKey = node.path("transactionKey").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        val orderId = node.path("orderId").takeIf { it.isTextual }?.asText()
        val amount = node.path("amount").takeIf { it.isIntegralNumber }?.longValue()
        val status = node.path("status").takeIf { it.isTextual }?.asText()
            ?.let { raw -> ExternalPaymentStatus.entries.firstOrNull { it.name == raw } }
        if (transactionKey == null || orderId == null || amount == null || status == null) return null
        return PaymentTransaction(transactionKey = transactionKey, providerOrderId = orderId, amount = amount, status = status)
    }

    private fun readTree(body: String): JsonNode? = runCatching { objectMapper.readTree(body) }.getOrNull()

    companion object {
        private const val PAYMENTS = "/api/v1/payments"
        private const val USER_HEADER = "X-USER-ID"
        private const val REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE"
    }
}
