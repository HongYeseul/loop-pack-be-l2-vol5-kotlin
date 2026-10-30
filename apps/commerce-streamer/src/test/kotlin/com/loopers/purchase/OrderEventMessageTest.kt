package com.loopers.purchase

import com.loopers.purchase.domain.InvalidPurchaseEventException
import com.loopers.purchase.domain.PurchaseActivity
import com.loopers.config.OrderEventJsonDeserializer
import com.loopers.purchase.interfaces.consumer.OrderConfirmedMessage
import com.loopers.purchase.interfaces.consumer.OrderConfirmedValidator
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.SerializationUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("JSON 자동 역직렬화와 주문 확정 계약 검증")
class OrderEventMessageTest {
    private val deserializer = OrderEventJsonDeserializer(PurchaseEvents.mapper)
    private val validator = OrderConfirmedValidator()

    private fun decode(key: String?, value: ByteArray?) =
        validator.validate(key, deserializer.deserialize("order-events", RecordHeaders(), value))

    @Test
    @DisplayName("JSON 형식과 품목 순서가 달라도 같은 구매 사실로 읽고 전체 품목을 비교한다")
    fun readsProducerV1AndIgnoresFormattingAndItemOrder() {
        val original = requireNotNull(decode("42", PurchaseEvents.payload()))
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload())
        val items = tree.path("items") as com.fasterxml.jackson.databind.node.ArrayNode
        val first = items.remove(0)
        items.add(first)
        val reversed = PurchaseEvents.mapper.createObjectNode()
        tree.fields().asSequence().toList().reversed().forEach { reversed.set<com.fasterxml.jackson.databind.JsonNode>(it.key, it.value) }
        val formatted = PurchaseEvents.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(reversed)
        assertThat(decode("42", formatted)).isEqualTo(original)
        assertThat(original.matches(listOf(PurchaseActivity(42, 8, 1, PurchaseEvents.time), PurchaseActivity(42, 7, 2, PurchaseEvents.time)))).isTrue()
        assertThat(original.matches(listOf(PurchaseActivity(42, 7, 2, PurchaseEvents.time)))).isFalse()
    }

    @Test
    @DisplayName("다른 이벤트는 건너뛰되 eventType·본문 누락과 key 불일치는 거부한다")
    fun ignoresOtherNormalTypesButNotMissingType() {
        assertThat(decode("42", "{\"eventType\":\"PaymentSucceeded\"}".toByteArray())).isNull()
        assertThrows<InvalidPurchaseEventException> { decode("42", "{}".toByteArray()) }
        assertThrows<InvalidPurchaseEventException> { decode("42", null) }
        assertThrows<InvalidPurchaseEventException> { decode("43", PurchaseEvents.payload()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
        "not-json", "[]", "{} {}",
    ],
    )
    @DisplayName("잘못된 JSON 구조는 listener 진입 전에 역직렬화 오류로 거부한다")
    fun rejectsInvalidEnvelopes(json: String) {
        assertThrows<SerializationException> { decode("42", json.toByteArray()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["schemaVersion", "eventId", "orderId", "confirmedAt", "items"])
    @DisplayName("주문 확정 이벤트의 필수 필드가 누락되면 거부한다")
    fun rejectsMissingFields(field: String) {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload()) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.remove(field)
        assertThrows<InvalidPurchaseEventException> { decode("42", PurchaseEvents.mapper.writeValueAsBytes(tree)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["zero", "fraction", "overflow", "duplicate", "object", "null", "wrongId", "precision"])
    @DisplayName("수량·상품 구성·주문 ID·이벤트 ID·시각이 계약에 맞지 않으면 거부한다")
    fun rejectsInvalidValues(kind: String) {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload()) as com.fasterxml.jackson.databind.node.ObjectNode
        val items = tree.path("items") as com.fasterxml.jackson.databind.node.ArrayNode
        val item = items[0] as com.fasterxml.jackson.databind.node.ObjectNode
        when (kind) {
            "zero" -> item.put("quantity", 0)
            "fraction" -> item.put("quantity", 1.5)
            "overflow" -> item.put("quantity", Long.MAX_VALUE)
            "duplicate" -> items.add(item.deepCopy())
            "object" -> tree.set<com.fasterxml.jackson.databind.JsonNode>("items", item)
            "null" -> tree.putNull("orderId")
            "wrongId" -> tree.put("eventId", "other")
            "precision" -> tree.put("confirmedAt", "2026-10-27T01:02:03.123456789Z")
        }
        val value = PurchaseEvents.mapper.writeValueAsBytes(tree)
        if (kind in setOf("fraction", "object")) {
            assertThrows<SerializationException> { decode("42", value) }
        } else {
            assertThrows<InvalidPurchaseEventException> { decode("42", value) }
        }
    }

    @Test
    @DisplayName("역직렬화기가 JSON을 주문 확정 DTO로 자동 변환하고 추가 필드를 무시한다")
    fun automaticallyBindsDtoAndIgnoresAdditionalFields() {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload()) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("paymentId", "payment-42")
        (tree.path("items")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("productName", "상품")
        val value = PurchaseEvents.mapper.writeValueAsBytes(tree)
        val message = deserializer.deserialize("order-events", RecordHeaders(), value)
        assertThat(message).isInstanceOf(OrderConfirmedMessage::class.java)
        assertThat((message as OrderConfirmedMessage).items?.first()?.quantity).isEqualTo(2L)
        assertThat(validator.validate("42", message)).isEqualTo(PurchaseEvents.purchase())
    }

    @Test
    @DisplayName("다른 이벤트는 주문 필드의 구조나 타입이 달라도 eventType으로 구분해 건너뛴다")
    fun skipsOtherEventsWithDifferentFieldShapes() {
        val json = """{"orderId":"external-id","items":{"payment":true},"confirmedAt":123,"eventType":"PaymentSucceeded"}"""
        assertThat(decode("external-id", json.toByteArray())).isNull()
    }

    @Test
    @DisplayName("지원하지 않는 버전은 DTO 변환 후 계약 검증에서 거부한다")
    fun rejectsUnsupportedVersionAfterDeserialization() {
        assertThrows<InvalidPurchaseEventException> {
            decode("42", """{"eventType":"OrderConfirmed","schemaVersion":2}""".toByteArray())
        }
    }

    @Test
    @DisplayName("역직렬화 실패 시 오류 처리기가 받을 헤더에 예외와 원문을 남긴다")
    fun errorHandlingDeserializerCapturesMalformedJson() {
        val value = "broken{".toByteArray()
        val headers = RecordHeaders()
        val handling = ErrorHandlingDeserializer(deserializer)
        assertThat(handling.deserialize("order-events", headers, value)).isNull()
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull()
        assertThat(headers.lastHeader(OrderEventJsonDeserializer.ORIGINAL_VALUE_HEADER).value()).isEqualTo(value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["fractionVersion", "textOrderId", "numericEventId", "numericTimestamp", "textQuantity", "booleanProductId"])
    @DisplayName("이벤트 필드의 잘못된 자료형을 숫자나 문자열로 자동 보정하지 않고 거부한다")
    fun rejectsScalarTypeCoercion(kind: String) {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload()) as com.fasterxml.jackson.databind.node.ObjectNode
        val item = tree.path("items")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        when (kind) {
            "fractionVersion" -> tree.put("schemaVersion", 1.5)
            "textOrderId" -> tree.put("orderId", "42")
            "numericEventId" -> tree.put("eventId", 42)
            "numericTimestamp" -> tree.put("confirmedAt", 123)
            "textQuantity" -> item.put("quantity", "2")
            "booleanProductId" -> item.put("productId", true)
        }
        assertThrows<SerializationException> { decode("42", PurchaseEvents.mapper.writeValueAsBytes(tree)) }
    }
}
