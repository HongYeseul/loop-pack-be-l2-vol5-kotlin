package com.loopers.user.domain.event

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UserEventTypeTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun serializesAsEventName() {
        val json = objectMapper.writeValueAsString(UserEventType.USER_SIGNED_UP)

        assertThat(json).isEqualTo("\"UserSignedUp\"")
    }

    @Test
    fun deserializesEventName() {
        val type = objectMapper.readValue<UserEventType>("\"UserSignedUp\"")

        assertThat(type).isEqualTo(UserEventType.USER_SIGNED_UP)
    }

    @Test
    fun rejectsOtherDomainEvent() {
        assertThatThrownBy { objectMapper.readValue<UserEventType>("\"OrderConfirmed\"") }
            .isInstanceOf(InvalidFormatException::class.java)
    }
}
