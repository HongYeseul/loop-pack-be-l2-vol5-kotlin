package com.loopers.purchase

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.loopers.purchase.domain.ConfirmedPurchase
import java.time.Instant

object PurchaseEvents {
    val mapper = jacksonObjectMapper()
    val time: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")

    fun purchase(orderId: Long = 42, quantity: Int = 2) = ConfirmedPurchase(
        orderId,
        time,
        listOf(ConfirmedPurchase.Item(7, quantity), ConfirmedPurchase.Item(8, 1)),
    )

    fun payload(orderId: Long = 42, quantity: Int = 2): ByteArray = mapper.writeValueAsBytes(
        mapOf(
            "eventType" to "OrderConfirmed",
            "schemaVersion" to 1,
            "eventId" to "order-confirmed:v1:$orderId",
            "orderId" to orderId,
            "confirmedAt" to time.toString(),
            "items" to listOf(mapOf("productId" to 7, "quantity" to quantity), mapOf("productId" to 8, "quantity" to 1)),
        ),
    )
}
