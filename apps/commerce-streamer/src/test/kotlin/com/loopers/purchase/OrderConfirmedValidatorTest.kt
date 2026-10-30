package com.loopers.purchase

import com.loopers.purchase.domain.InvalidPurchaseEventException
import com.loopers.purchase.interfaces.consumer.OrderConfirmedMessage
import com.loopers.purchase.interfaces.consumer.OrderConfirmedValidator
import com.loopers.purchase.interfaces.consumer.OtherOrderEventMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("주문 확정 DTO의 업무 계약 검증")
class OrderConfirmedValidatorTest {
    private val validator = OrderConfirmedValidator()
    private val message = OrderConfirmedMessage(
        "OrderConfirmed",
        1,
        "order-confirmed:v1:42",
        42,
        PurchaseEvents.time.toString(),
        listOf(OrderConfirmedMessage.Item(7, 2), OrderConfirmedMessage.Item(8, 1)),
    )

    @Test
    @DisplayName("유효한 주문 확정 DTO를 구매 사실로 변환한다")
    fun acceptsValidMessage() {
        assertThat(validator.validate("42", message)).isEqualTo(PurchaseEvents.purchase())
    }

    @Test
    @DisplayName("품목 순서가 달라도 상품 ID순으로 정규화한다")
    fun normalizesItemOrder() {
        val reordered = message.copy(items = message.items!!.reversed())

        assertThat(validator.validate("42", reordered)).isEqualTo(PurchaseEvents.purchase())
    }

    @Test
    @DisplayName("주문 확정 외 정상 이벤트는 처리 대상에서 제외한다")
    fun skipsOtherEventTypes() {
        assertThat(validator.validate("42", OtherOrderEventMessage("PaymentSucceeded"))).isNull()
    }

    @Test
    @DisplayName("eventType이 없으면 거부한다")
    fun rejectsMissingEventType() {
        assertThrows<InvalidPurchaseEventException> { validator.validate("42", OtherOrderEventMessage()) }
    }

    @Test
    @DisplayName("이벤트 본문이 없으면 거부한다")
    fun rejectsMissingBody() {
        assertThrows<InvalidPurchaseEventException> { validator.validate("42", null) }
    }

    @Test
    @DisplayName("key와 주문 ID가 다르면 거부한다")
    fun rejectsMismatchingKey() {
        assertThrows<InvalidPurchaseEventException> { validator.validate("43", message) }
    }

    @Test
    @DisplayName("지원하지 않는 버전은 거부한다")
    fun rejectsUnsupportedVersion() {
        assertThrows<InvalidPurchaseEventException> { validator.validate("42", message.copy(schemaVersion = 2)) }
    }

    @ParameterizedTest(name = "필수 필드 누락: {0}")
    @ValueSource(strings = ["schemaVersion", "eventId", "orderId", "confirmedAt", "items"])
    @DisplayName("주문 확정 필수 필드가 없으면 거부한다")
    fun rejectsMissingFields(field: String) {
        val invalid = when (field) {
            "schemaVersion" -> message.copy(schemaVersion = null)
            "eventId" -> message.copy(eventId = null)
            "orderId" -> message.copy(orderId = null)
            "confirmedAt" -> message.copy(confirmedAt = null)
            "items" -> message.copy(items = null)
            else -> error("알 수 없는 필드: $field")
        }

        assertThrows<InvalidPurchaseEventException> { validator.validate("42", invalid) }
    }

    @ParameterizedTest(name = "계약 위반: {0}")
    @ValueSource(strings = ["zeroQuantity", "overflowQuantity", "duplicateProduct", "wrongEventId", "timestampPrecision"])
    @DisplayName("구매 사실의 수량·상품·이벤트 ID·시각 계약을 위반하면 거부한다")
    fun rejectsInvalidValues(kind: String) {
        val first = message.items!!.first()!!
        val invalid = when (kind) {
            "zeroQuantity" -> message.copy(items = listOf(first.copy(quantity = 0)))
            "overflowQuantity" -> message.copy(items = listOf(first.copy(quantity = Long.MAX_VALUE)))
            "duplicateProduct" -> message.copy(items = listOf(first, first))
            "wrongEventId" -> message.copy(eventId = "other")
            "timestampPrecision" -> message.copy(confirmedAt = "2026-10-27T01:02:03.123456789Z")
            else -> error("알 수 없는 사례: $kind")
        }

        assertThrows<InvalidPurchaseEventException> { validator.validate("42", invalid) }
    }
}
