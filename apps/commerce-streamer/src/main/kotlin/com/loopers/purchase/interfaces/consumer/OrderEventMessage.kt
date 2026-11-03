package com.loopers.purchase.interfaces.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "eventType",
    visible = true,
    defaultImpl = OtherOrderEventMessage::class,
)
@JsonSubTypes(JsonSubTypes.Type(value = OrderConfirmedMessage::class, name = "OrderConfirmed"))
sealed interface OrderEventMessage {
    val eventType: String?
}

data class OtherOrderEventMessage(override val eventType: String? = null) : OrderEventMessage

data class OrderConfirmedMessage(
    override val eventType: String? = null,
    val schemaVersion: Int? = null,
    val eventId: String? = null,
    val orderId: Long? = null,
    val confirmedAt: String? = null,
    val items: List<Item?>? = null,
) : OrderEventMessage {
    data class Item(val productId: Long? = null, val quantity: Long? = null)
}
