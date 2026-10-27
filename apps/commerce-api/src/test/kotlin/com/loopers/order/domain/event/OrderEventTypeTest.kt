package com.loopers.order.domain.event

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderEventTypeTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun serializesAsEventName() {
        val json = objectMapper.writeValueAsString(OrderEventType.ORDER_CONFIRMED)

        assertThat(json).isEqualTo("\"OrderConfirmed\"")
    }

    @Test
    fun deserializesEventName() {
        val type = objectMapper.readValue<OrderEventType>("\"OrderConfirmed\"")

        assertThat(type).isEqualTo(OrderEventType.ORDER_CONFIRMED)
    }

    @Test
    fun rejectsOtherDomainEvent() {
        assertThatThrownBy { objectMapper.readValue<OrderEventType>("\"ProductDetailQueried\"") }
            .isInstanceOf(InvalidFormatException::class.java)
    }
}
