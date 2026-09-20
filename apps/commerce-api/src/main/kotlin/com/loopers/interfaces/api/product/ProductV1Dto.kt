package com.loopers.interfaces.api.product

import com.loopers.application.product.ProductInfo
import com.loopers.domain.product.ProductStatus

class ProductV1Dto {
    /** 상품 응답에 함께 나가는 브랜드 정보 (P-10). 고객에게는 id 와 이름뿐이다 (D-10). */
    data class BrandResponse(val id: Long, val name: String) {
        companion object {
            fun from(info: ProductInfo): BrandResponse = BrandResponse(id = info.brandId, name = info.brandName)
        }
    }

    /**
     * 고객이 보는 판매 상태 — **넷이다** (P-37 · DS-11). 저장하는 것은 셋뿐이고([ProductStatus])
     * `SOLD_OUT` 은 `stock == 0` 에서 파생한다.
     *
     * **계산이 `interfaces` 에 있는 이유**: 저장된 상태와 재고를 어떻게 한 단어로 읽어줄지는
     * 응답의 문제다. 도메인은 두 값을 따로 들고 있으면 된다.
     */
    enum class SaleStatus {
        ON_SALE,
        SOLD_OUT,
        SUSPENDED,
        DISCONTINUED,
        ;

        companion object {
            /** **판정 순서가 규칙이다** — 단종 → 판매중지 → 재고없음 → 판매중 (P-37). */
            fun of(status: ProductStatus, stock: Int): SaleStatus = when {
                status == ProductStatus.DISCONTINUED -> DISCONTINUED
                status == ProductStatus.SUSPENDED -> SUSPENDED
                stock == 0 -> SOLD_OUT
                else -> ON_SALE
            }
        }
    }

    /**
     * 목록 한 줄 (C-2 · D-10).
     *
     * **`stock` 도 `status` 도 없다.** `ProductInfo` 는 둘 다 들고 있지만 여기서 가려낸다 (DS-5).
     * 구조로 막지 못하는 자리라 E2E 가 "이 키들이 없다"를 확인한다.
     *
     * 목록에는 판매중인 상품만 나오므로(P-39) 판매 상태는 싣지 않는다 — 넷을 구분할 자리는
     * 상세다 ([ProductDetailResponse]).
     */
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

    /** 상세 (C-3). 목록과 다른 것은 **판매 상태**뿐이다 — 여기서는 판매중이 아닌 상품도 조회된다 (P-39). */
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
