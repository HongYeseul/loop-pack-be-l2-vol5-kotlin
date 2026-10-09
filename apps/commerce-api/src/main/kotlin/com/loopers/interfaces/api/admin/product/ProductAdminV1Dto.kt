package com.loopers.interfaces.api.admin.product

import com.loopers.application.product.ProductInfo
import com.loopers.domain.product.ProductStatus
import java.time.ZonedDateTime

class ProductAdminV1Dto {
    /** stock 생략 시 0 으로 시작, 재고는 별도 API 로 설정 */
    data class CreateRequest(
        val brandId: Long,
        val name: String,
        val price: Long,
        val stock: Int? = null,
    )

    /** brandId 없음 (브랜드 변경 불가) */
    data class UpdateRequest(val name: String, val price: Long)

    /** 증감이 아닌 최종 수량 (재호출해도 결과 동일) */
    data class StockRequest(val quantity: Int)

    /**
     * 최종 상태 설정, SOLD_OUT 은 재고에서 파생되는 값이라 enum 에 없음
     * 없는 값은 Jackson 이 거절해 BAD_REQUEST
     */
    data class StatusRequest(val status: ProductStatus)

    /** 관리자용 상품, 재고와 저장된 판매 상태를 그대로 노출 (고객용 넷으로 합치지 않음) */
    data class ProductResponse(
        val id: Long,
        val brandId: Long,
        val brandName: String,
        val name: String,
        val price: Long,
        val stock: Int,
        val status: ProductStatus,
        val purchasable: Boolean,
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
