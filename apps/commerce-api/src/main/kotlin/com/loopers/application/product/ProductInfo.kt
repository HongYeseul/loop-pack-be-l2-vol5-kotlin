package com.loopers.application.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductStatus
import java.time.ZonedDateTime

/**
 * `application` 은 **누가 보는지 모릅니다** (DS-5). 필요한 값을 전부 담고 가려내는 것은
 * `interfaces` 의 DTO 입니다. 구조로 막을 수 없는 자리라 "고객 응답에 `stock` 키가 없다"를
 * 테스트가 지킵니다.
 *
 * **판매 상태 넷은 여기서 만들지 않습니다.** 재고없음은 보여줄 때 계산하는 값이라
 * `interfaces` 에 있고(P-37 · DS-11), 여기서는 재료([status] 와 [stock])만 나릅니다.
 *
 * [likeCount] 는 상품에 저장된 숫자가 아니라 **관계를 센 값**입니다 (P-15). 그래서 `Product` 가
 * 아니라 조립하는 쪽에서 들어옵니다.
 */
data class ProductInfo(
    val id: Long,
    val brandId: Long,
    val brandName: String,
    val name: String,
    val price: Long,
    /** 고객 응답에는 나가지 않습니다 (D-10). */
    val stock: Int,
    val status: ProductStatus,
    /** 상태와 재고를 함께 본 결과 (D-10 · "구매 가능 여부"). */
    val purchasable: Boolean,
    /** 관계를 센 값 (P-10 · P-15). 아무도 안 눌렀으면 0 입니다. */
    val likeCount: Long,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
    val deletedAt: ZonedDateTime?,
) {
    companion object {
        fun of(product: Product, brand: Brand, likeCount: Long): ProductInfo = ProductInfo(
            id = product.id,
            brandId = product.brandId,
            brandName = brand.name,
            name = product.name,
            price = product.price,
            stock = product.stock,
            status = product.status,
            purchasable = product.purchasable,
            likeCount = likeCount,
            createdAt = product.createdAt,
            updatedAt = product.updatedAt,
            deletedAt = product.deletedAt,
        )
    }
}
