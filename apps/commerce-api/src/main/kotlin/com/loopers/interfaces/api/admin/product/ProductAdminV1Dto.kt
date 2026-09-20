package com.loopers.interfaces.api.admin.product

import com.loopers.application.product.ProductInfo
import com.loopers.domain.product.ProductStatus
import java.time.ZonedDateTime

class ProductAdminV1Dto {
    /** A-7 · 생성. `stock` 을 생략하면 0 으로 시작한다 — 재고는 A-11 로 따로 설정할 수 있다 (P-07 · 기획 S-1). */
    data class CreateRequest(
        val brandId: Long,
        val name: String,
        val price: Long,
        val stock: Int? = null,
    )

    /** A-9 · 수정. **`brandId` 가 없다.** 상품의 브랜드는 바꾸지 않는다 (P-05). */
    data class UpdateRequest(val name: String, val price: Long)

    /** A-11 · 재고. **증감이 아니라 최종 수량이다** (P-07). 두 번 보내도 결과가 같다. */
    data class StockRequest(val quantity: Int)

    /**
     * A-16 · 판매 상태. 최종 상태를 설정한다 (P-36).
     *
     * **`SOLD_OUT` 을 받을 수 없다** — [ProductStatus] 에 없기 때문이다. 재고없음은 재고에서
     * 따라오는 값이라 관리자가 설정할 대상이 아니고(P-37), 그 금지를 타입이 지킨다.
     * 없는 값이 오면 Jackson 이 거절해 `BAD_REQUEST` 가 된다.
     */
    data class StatusRequest(val status: ProductStatus)

    /**
     * 관리자가 보는 상품 — 고객 응답보다 자세하다 (P-33 · D-10).
     *
     * **재고 수량과 저장된 판매 상태를 그대로 보여준다.** 재고를 숨기면 "왜 이 상품이 안 팔리는지",
     * 삭제 시각을 숨기면 "왜 이 브랜드가 안 지워지는지"(P-11)에 답할 수 없다.
     * 고객처럼 넷으로 합쳐 보여주지 않는 이유도 같다 — 관리자는 **자기가 바꿀 수 있는 것**(저장된 상태)과
     * **재고에서 따라오는 것**(재고 0)을 구분해서 봐야 한다.
     */
    data class ProductResponse(
        val id: Long,
        val brandId: Long,
        val brandName: String,
        val name: String,
        val price: Long,
        val stock: Int,
        val status: ProductStatus,
        val purchasable: Boolean,
        /** 고객과 **같은 숫자**를 본다 (기획 6절 표). 관계 자체를 보는 API 는 이번 범위 밖이다. */
        val likeCount: Long,
        val createdAt: ZonedDateTime,
        val updatedAt: ZonedDateTime,
        val deletedAt: ZonedDateTime?,
    ) {
        companion object {
            fun from(info: ProductInfo): ProductResponse = ProductResponse(
                id = info.id,
                brandId = info.brandId,
                brandName = info.brandName,
                name = info.name,
                price = info.price,
                stock = info.stock,
                status = info.status,
                purchasable = info.purchasable,
                likeCount = info.likeCount,
                createdAt = info.createdAt,
                updatedAt = info.updatedAt,
                deletedAt = info.deletedAt,
            )
        }
    }
}
