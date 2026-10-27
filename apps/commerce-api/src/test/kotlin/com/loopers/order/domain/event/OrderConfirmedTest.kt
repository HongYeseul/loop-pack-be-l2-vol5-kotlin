package com.loopers.order.domain.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.fixture.OrderFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.ReflectionTestUtils
import java.time.ZonedDateTime

class OrderConfirmedTest {
    private val confirmedAt = ZonedDateTime.parse("2026-10-27T01:02:03.123456789Z")
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun preservesWireContractAndNormalizesItems() {
        val order = OrderFixture.order(
            userId = 1L,
            now = confirmedAt,
            lines = listOf(OrderFixture.line(7L, quantity = 2), OrderFixture.line(8L, quantity = 1)),
        )
        ReflectionTestUtils.setField(order, "id", 42L)
        order.confirm(confirmedAt)
        val items = listOf(
            OrderFixture.item(42L, productId = 8L, quantity = 1),
            OrderFixture.item(42L, productId = 7L, quantity = 1),
            OrderFixture.item(42L, productId = 7L, quantity = 1),
        )

        val event = OrderConfirmed.from(order, items)
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
        assertThat(event.items).containsExactly(OrderConfirmed.Item(7L, 2), OrderConfirmed.Item(8L, 1))
        assertThat(OrderConfirmed.from(order, items.reversed())).isEqualTo(event)
    }

    @Test
    fun rejectsDraftAndItemsFromAnotherOrder() {
        val order = OrderFixture.order(userId = 1L, now = confirmedAt)
        ReflectionTestUtils.setField(order, "id", 42L)
        assertThrows<IllegalArgumentException> {
            OrderConfirmed.from(order, listOf(OrderFixture.item(42L, productId = 7L)))
        }

        order.confirm(confirmedAt)
        assertThrows<IllegalArgumentException> {
            OrderConfirmed.from(order, listOf(OrderFixture.item(43L, productId = 7L)))
        }
    }
}
