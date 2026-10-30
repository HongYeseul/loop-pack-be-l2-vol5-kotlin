package com.loopers.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.loopers.purchase.interfaces.consumer.OrderEventMessage
import org.apache.kafka.common.header.Headers
import org.springframework.kafka.support.serializer.JsonDeserializer

class OrderEventJsonDeserializer(mapper: ObjectMapper) : JsonDeserializer<OrderEventMessage>(
    OrderEventMessage::class.java,
    mapper.copy().apply {
        enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        disable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.ACCEPT_FLOAT_AS_INT,
            DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
        )
        coercionConfigFor(LogicalType.Integer)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
    },
    false,
) {
    override fun deserialize(topic: String, headers: Headers, data: ByteArray?): OrderEventMessage? {
        // 역직렬화 성공 후 검증·저장에 실패해도 DLT에 원문 유지
        headers.remove(ORIGINAL_VALUE_HEADER)
        if (data != null) headers.add(ORIGINAL_VALUE_HEADER, data)
        return super.deserialize(topic, headers, data)
    }

    companion object {
        const val ORIGINAL_VALUE_HEADER = "purchase-original-value"
    }
}
