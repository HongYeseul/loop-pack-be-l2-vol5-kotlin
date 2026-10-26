package com.loopers.product.interfaces.api

import com.loopers.product.application.ProductInfo
import com.loopers.product.domain.ProductStatus

class ProductV1Dto {
    data class BrandResponse(val id: Long, val name: String) {
        companion object {
            fun from(info: ProductInfo): BrandResponse = BrandResponse(id = info.brandId, name = info.brandName)
        }
    }

    /** 고객용 판매 상태 넷, 저장은 셋뿐이고 SOLD_OUT 은 stock == 0 에서 파생 */
    enum class SaleStatus {
        ON_SALE,
        SOLD_OUT,
        SUSPENDED,
        DISCONTINUED,
        ;

        companion object {
            /** 판정 순서가 규칙: 단종 → 판매중지 → 재고없음 → 판매중 */
            fun of(status: ProductStatus, stock: Int): SaleStatus = when {
                status == ProductStatus.DISCONTINUED -> DISCONTINUED
                status == ProductStatus.SUSPENDED -> SUSPENDED
                stock == 0 -> SOLD_OUT
                else -> ON_SALE
            }
        }
    }

    /** 목록 한 줄, stock·status 제외 (목록은 판매중만 노출), E2E 가 키 부재 검증 */
    data class ProductResponse(
        val id: Long,
        val brand: BrandResponse,
        val name: String,
        val price: Long,
        val purchasable: Boolean,
        val likeCount: Long,
    ) {
        companion object {
            fun from(info: ProductInfo): ProductResponse = ProductResponse(
                id = info.id,
                brand = BrandResponse.from(info),
                name = info.name,
                price = info.price,
                purchasable = info.purchasable,
                likeCount = info.likeCount,
            )
        }
    }

    /** 목록과 달리 판매 상태 포함 (판매중이 아닌 상품도 조회 가능) */
    data class ProductDetailResponse(
        val id: Long,
        val brand: BrandResponse,
        val name: String,
        val price: Long,
        val purchasable: Boolean,
        val likeCount: Long,
        val saleStatus: SaleStatus,
    ) {
        companion object {
            fun from(info: ProductInfo): ProductDetailResponse = ProductDetailResponse(
                id = info.id,
                brand = BrandResponse.from(info),
                name = info.name,
                price = info.price,
                purchasable = info.purchasable,
                likeCount = info.likeCount,
                saleStatus = SaleStatus.of(info.status, info.stock),
            )
        }
    }
}
