package com.loopers.application.order

/** 주문 생성 입력, 단가 없음 (요청자 입력 금지, 상품에서 복사) */
data class OrderCommand(
    val items: List<Item>,
) {
    val productIds: List<Long> get() = items.map { it.productId }

    data class Item(
        val productId: Long,
        val quantity: Int,
    )
}
