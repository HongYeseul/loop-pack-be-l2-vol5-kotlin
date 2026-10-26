package com.loopers.product.domain

/** 재고없음은 저장하지 않고 재고에서 파생 (진실의 출처 이중화 방지) */
enum class ProductStatus {
    /** 기본값 */
    ON_SALE,

    /** 되돌릴 수 있음 */
    SUSPENDED,

    /** 최종 상태 */
    DISCONTINUED,
    ;

    /** 재고는 보지 않음 (재고없음은 별개 축) */
    val isOnSale: Boolean get() = this == ON_SALE

    /** 같은 상태로의 설정도 허용, 막는 것은 단종 되돌리기뿐 */
    fun canTransitionTo(next: ProductStatus): Boolean = next in allowedNext

    private val allowedNext: Set<ProductStatus>
        get() = when (this) {
            ON_SALE -> setOf(ON_SALE, SUSPENDED, DISCONTINUED)
            SUSPENDED -> setOf(SUSPENDED, ON_SALE, DISCONTINUED)
            DISCONTINUED -> setOf(DISCONTINUED)
        }
}
