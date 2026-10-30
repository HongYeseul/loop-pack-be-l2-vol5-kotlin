package com.loopers.purchase

import com.loopers.config.OrderEventJsonDeserializer
import com.loopers.purchase.interfaces.consumer.OrderConfirmedMessage
import com.loopers.purchase.interfaces.consumer.OtherOrderEventMessage
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

@DisplayName("주문 이벤트 JSON 자동 역직렬화")
class OrderEventJsonDeserializerTest {
    private val deserializer = OrderEventJsonDeserializer(PurchaseEvents.mapper)

    @Test
    @DisplayName("JSON 공백과 필드·품목 순서가 달라도 DTO의 필드 값을 유지한다")
    fun ignoresFormattingAndFieldOrder() {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload())
        val items = tree.path("items") as com.fasterxml.jackson.databind.node.ArrayNode
        val first = items.remove(0)
        items.add(first)
        val reversed = PurchaseEvents.mapper.createObjectNode()
        tree.fields().asSequence().toList().reversed().forEach { reversed.set<com.fasterxml.jackson.databind.JsonNode>(it.key, it.value) }
        val formatted = PurchaseEvents.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(reversed)

        val message = deserializer.deserialize("order-events", RecordHeaders(), formatted) as OrderConfirmedMessage

        assertThat(message).isEqualTo(
            OrderConfirmedMessage(
                "OrderConfirmed",
                1,
                "order-confirmed:v1:42",
                42,
                PurchaseEvents.time.toString(),
                listOf(OrderConfirmedMessage.Item(8, 1), OrderConfirmedMessage.Item(7, 2)),
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
        "not-json", "[]", "{} {}",
    ],
    )
    @DisplayName("잘못된 JSON 구조는 listener 진입 전에 역직렬화 오류로 거부한다")
    fun rejectsInvalidEnvelopes(json: String) {
        assertThrows<SerializationException> { deserializer.deserialize("order-events", RecordHeaders(), json.toByteArray()) }
    }

    @ParameterizedTest(name = "JSON 필드 누락: {0}")
    @ValueSource(strings = ["schemaVersion", "eventId", "orderId", "confirmedAt", "items"])
    @DisplayName("누락된 필드는 DTO에 null로 전달해 업무 검증에서 판단하도록 한다")
    fun bindsMissingFieldsAsNull(field: String) {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload()) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.remove(field)

        val message = deserializer.deserialize("order-events", RecordHeaders(), PurchaseEvents.mapper.writeValueAsBytes(tree)) as OrderConfirmedMessage

        val value = when (field) {
            "schemaVersion" -> message.schemaVersion
            "eventId" -> message.eventId
            "orderId" -> message.orderId
            "confirmedAt" -> message.confirmedAt
            "items" -> message.items
            else -> error("알 수 없는 필드: $field")
        }
        assertThat(value).isNull()
    }

    @Test
    @DisplayName("역직렬화기가 JSON을 주문 확정 DTO로 자동 변환하고 추가 필드를 무시한다")
    fun automaticallyBindsDtoAndIgnoresAdditionalFields() {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload()) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("paymentId", "payment-42")
        (tree.path("items")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("productName", "상품")
        val value = PurchaseEvents.mapper.writeValueAsBytes(tree)
        val message = deserializer.deserialize("order-events", RecordHeaders(), value)
        assertThat(message).isEqualTo(
            OrderConfirmedMessage(
                "OrderConfirmed",
                1,
                "order-confirmed:v1:42",
                42,
                PurchaseEvents.time.toString(),
                listOf(OrderConfirmedMessage.Item(7, 2), OrderConfirmedMessage.Item(8, 1)),
            ),
        )
    }

    @Test
    @DisplayName("다른 이벤트는 주문 필드의 구조나 타입이 달라도 해당 타입의 DTO로 읽는다")
    fun skipsOtherEventsWithDifferentFieldShapes() {
        val json = """{"orderId":"external-id","items":{"payment":true},"confirmedAt":123,"eventType":"PaymentSucceeded"}"""
        assertThat(deserializer.deserialize("order-events", RecordHeaders(), json.toByteArray()))
            .isEqualTo(OtherOrderEventMessage("PaymentSucceeded"))
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
    @ValueSource(strings = ["fractionVersion", "textOrderId", "numericEventId", "numericTimestamp", "textQuantity", "booleanProductId", "fractionQuantity", "objectItems"])
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
            "fractionQuantity" -> item.put("quantity", 1.5)
            "objectItems" -> tree.set<com.fasterxml.jackson.databind.JsonNode>("items", item)
        }
        assertThrows<SerializationException> { deserializer.deserialize("order-events", RecordHeaders(), PurchaseEvents.mapper.writeValueAsBytes(tree)) }
    }
}
