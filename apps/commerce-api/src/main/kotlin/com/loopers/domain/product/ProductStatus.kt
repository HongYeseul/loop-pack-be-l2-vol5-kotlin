package com.loopers.domain.product

/**
 * 상품 판매 상태 (P-36 · DS-11).
 *
 * **재고없음이 여기 없는 것이 요점이다** (P-37). 상태로도 저장하면 진실의 출처가 둘이 되어
 * 재고를 바꾸는 모든 경로에서 함께 맞춰야 하고, 한 군데만 빠뜨리면
 * **재고가 0 인데 판매중인 상품**이 생긴다. 파생하면 어긋날 수가 없다 (P-15 와 같은 판단).
 *
 * 삭제와도 다른 축이다 — 그래서 P-11(브랜드 삭제)은 판매 상태를 보지 않는다.
 */
enum class ProductStatus {
    /** 판매중. 기본값이다. */
    ON_SALE,

    /** 판매중지. **되돌릴 수 있다.** 잠시 내려두는 경우다. */
    SUSPENDED,

    /** 단종. 최종 상태다. 되돌릴 수 없다. */
    DISCONTINUED,
    ;

    /** 재고는 보지 않는다. 재고없음은 다른 축이다 (P-37). */
    val isOnSale: Boolean get() = this == ON_SALE

    /**
     * A-16 은 전이가 아니라 **최종 상태 설정**이라(설계 6-3절) 같은 상태로의 설정도 허용한다 —
     * A-11(재고 설정)과 같은 규칙으로 읽히게 한다 (P-07).
     *
     * 막는 것은 **단종을 되돌리는 것** 하나뿐이다 (P-36).
     */
    fun canTransitionTo(next: ProductStatus): Boolean = next in allowedNext

    private val allowedNext: Set<ProductStatus>
        get() = when (this) {
            ON_SALE -> setOf(ON_SALE, SUSPENDED, DISCONTINUED)
            SUSPENDED -> setOf(SUSPENDED, ON_SALE, DISCONTINUED)
            DISCONTINUED -> setOf(DISCONTINUED)
        }
}
