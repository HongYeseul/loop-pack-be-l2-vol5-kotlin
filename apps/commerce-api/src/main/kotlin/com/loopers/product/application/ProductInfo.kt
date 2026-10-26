package com.loopers.product.application

import com.loopers.brand.domain.Brand
import com.loopers.product.domain.Product
import com.loopers.product.domain.ProductStatus
import java.time.ZonedDateTime

/**
 * 필요한 값을 전부 담음, 가림은 interfaces DTO 담당
 * 판매 상태 넷은 interfaces 에서 계산 (재고없음은 표시용 파생값)
 */
data class ProductInfo(
    val id: Long,
    val brandId: Long,
    val brandName: String,
    val name: String,
    val price: Long,
    /** 고객 응답 제외 */
    val stock: Int,
    val status: ProductStatus,
    /** 상태와 재고를 함께 본 구매 가능 여부 */
    val purchasable: Boolean,
    /** 관계 집계값, 없으면 0 */
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
