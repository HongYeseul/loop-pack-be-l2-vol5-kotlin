package com.loopers.purchase.interfaces.consumer

import com.loopers.purchase.domain.ConfirmedPurchase
import com.loopers.purchase.domain.InvalidPurchaseEventException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class OrderConfirmedValidator {
    fun validate(key: String?, message: OrderEventMessage?): ConfirmedPurchase? {
        try {
            val event = requireNotNull(message) { "이벤트 본문이 없습니다." }
            check(!event.eventType.isNullOrBlank()) { "eventType 문자열이 필요합니다." }
            if (event.eventType != "OrderConfirmed") return null
            check(event is OrderConfirmedMessage) { "주문 확정 메시지가 필요합니다." }
            check(event.schemaVersion == 1) { "지원하지 않는 이벤트 버전입니다." }
            val orderId = requireNotNull(event.orderId) { "주문 ID가 필요합니다." }
            check(key == orderId.toString()) { "key와 주문 ID가 다릅니다." }
            val items = requireNotNull(event.items) { "품목 배열이 필요합니다." }
            val purchase = ConfirmedPurchase(
                orderId,
                Instant.parse(requireNotNull(event.confirmedAt) { "확정 시각이 필요합니다." }),
                items.map {
                    val item = requireNotNull(it) { "품목이 필요합니다." }
                    val quantity = requireNotNull(item.quantity) { "구매 수량이 필요합니다." }
                    check(quantity in 1..Int.MAX_VALUE.toLong()) { "구매 수량이 유효하지 않습니다." }
                    ConfirmedPurchase.Item(requireNotNull(item.productId) { "상품 ID가 필요합니다." }, quantity.toInt())
                }.sortedBy { it.productId },
            )
            check(event.eventId == purchase.eventId) { "eventId가 주문 확정 계약과 다릅니다." }
            return purchase
        } catch (error: InvalidPurchaseEventException) {
            throw error
        } catch (error: Exception) {
            throw InvalidPurchaseEventException("주문 이벤트 계약이 유효하지 않습니다: ${error.message}", error)
        }
    }
}
