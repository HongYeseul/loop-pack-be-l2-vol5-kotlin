package com.loopers.product.domain.event

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProductEventTypeTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun serializesAsEventName() {
        val json = objectMapper.writeValueAsString(ProductEventType.PRODUCT_DETAIL_QUERIED)

        assertThat(json).isEqualTo("\"ProductDetailQueried\"")
    }

    @Test
    fun deserializesEventName() {
        val type = objectMapper.readValue<ProductEventType>("\"ProductDetailQueried\"")

        assertThat(type).isEqualTo(ProductEventType.PRODUCT_DETAIL_QUERIED)
    }

    @Test
    fun rejectsOtherDomainEvent() {
        assertThatThrownBy { objectMapper.readValue<ProductEventType>("\"UserSignedUp\"") }
            .isInstanceOf(InvalidFormatException::class.java)
    }
}
