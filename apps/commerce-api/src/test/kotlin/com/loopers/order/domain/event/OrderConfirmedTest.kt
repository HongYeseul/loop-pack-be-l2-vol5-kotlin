package com.loopers.order.domain.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.fixture.OrderFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.ReflectionTestUtils
import java.time.ZonedDateTime

@DisplayName("주문 확정 이벤트 생성과 JSON 계약")
class OrderConfirmedTest {
    private val confirmedAt = ZonedDateTime.parse("2026-10-27T01:02:03.123456789Z")
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    @DisplayName("주문 확정 이벤트를 v1 JSON 계약으로 직렬화한다")
    fun preservesWireContract() {
        val event = OrderConfirmed.from(confirmedOrder(), listOf(OrderFixture.item(42L, productId = 7L, quantity = 2)))

        val json = objectMapper.readTree(objectMapper.writeValueAsBytes(event))

        assertThat(json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "eventId",
            "eventType",
            "schemaVersion",
            "orderId",
            "confirmedAt",
            "items",
        )
        assertThat(json.path("eventType").asText()).isEqualTo("OrderConfirmed")
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1)
        assertThat(json.path("eventId").asText()).isEqualTo("order-confirmed:v1:42")
        assertThat(json.path("orderId").asLong()).isEqualTo(42L)
        assertThat(json.path("confirmedAt").asText()).isEqualTo("2026-10-27T01:02:03.123456Z")
    }

    @Test
    @DisplayName("같은 상품의 수량을 합산하고 상품 ID순으로 품목을 구성한다")
    fun normalizesItems() {
        val items = listOf(
            OrderFixture.item(42L, productId = 8L, quantity = 1),
            OrderFixture.item(42L, productId = 7L, quantity = 1),
            OrderFixture.item(42L, productId = 7L, quantity = 1),
        )

        val event = OrderConfirmed.from(confirmedOrder(), items)

        assertThat(event.items).containsExactly(OrderConfirmed.Item(7L, 2), OrderConfirmed.Item(8L, 1))
    }

    @Test
    @DisplayName("원본 품목의 순서가 바뀌어도 같은 확정 이벤트를 만든다")
    fun ignoresItemOrder() {
        val order = confirmedOrder()
        val items = listOf(OrderFixture.item(42L, productId = 7L), OrderFixture.item(42L, productId = 8L))

        assertThat(OrderConfirmed.from(order, items.reversed())).isEqualTo(OrderConfirmed.from(order, items))
    }

    @Test
    @DisplayName("확정되지 않은 주문으로 이벤트를 만들 수 없다")
    fun rejectsDraftOrder() {
        val order = OrderFixture.order(userId = 1L, now = confirmedAt)
        ReflectionTestUtils.setField(order, "id", 42L)

        assertThrows<IllegalArgumentException> {
            OrderConfirmed.from(order, listOf(OrderFixture.item(42L, productId = 7L)))
        }
    }

    @Test
    @DisplayName("다른 주문의 품목으로 확정 이벤트를 만들 수 없다")
    fun rejectsItemsFromAnotherOrder() {
        assertThrows<IllegalArgumentException> {
            OrderConfirmed.from(confirmedOrder(), listOf(OrderFixture.item(43L, productId = 7L)))
        }
    }

    private fun confirmedOrder() = OrderFixture.order(
        userId = 1L,
        now = confirmedAt,
        lines = listOf(OrderFixture.line(7L, quantity = 2), OrderFixture.line(8L, quantity = 1)),
    ).also {
        ReflectionTestUtils.setField(it, "id", 42L)
        it.confirm(confirmedAt)
    }
}
