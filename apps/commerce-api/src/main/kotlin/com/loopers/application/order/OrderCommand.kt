package com.loopers.application.order

/**
 * C-9 · 주문 생성 입력.
 *
 * **단가가 없다.** 요청자가 값을 보내면 서버가 그 값으로 팔게 된다 — 단가는 상품에서 복사한다 (P-28).
 *
 * 수량이 양수인지(P-24), 같은 상품이 두 줄인지(P-25)도 여기서 보지 않는다. 규칙 위반은 `domain` 이고
 * `interfaces` 는 형식만 본다 (DS-2).
 */
data class OrderCommand(
    val items: List<Item>,
) {
    val productIds: List<Long> get() = items.map { it.productId }

    data class Item(
        val productId: Long,
        val quantity: Int,
    )
}
