package com.loopers.interfaces.api.admin.brand

import com.loopers.application.brand.BrandDetailInfo
import com.loopers.application.brand.BrandInfo
import java.time.ZonedDateTime

class BrandAdminV1Dto {
    data class CreateRequest(val name: String)

    data class UpdateRequest(val name: String)

    /**
     * 관리자가 보는 브랜드 — 고객 응답보다 자세하다 (P-33 · D-10).
     *
     * 삭제 시각을 숨기면 "왜 이 브랜드가 안 지워지는지"(P-11)에 답할 수 없다.
     */
    data class BrandResponse(
        val id: Long,
        val name: String,
        val createdAt: ZonedDateTime,
        val updatedAt: ZonedDateTime,
        val deletedAt: ZonedDateTime?,
    ) {
        companion object {
            fun from(info: BrandInfo): BrandResponse = BrandResponse(
                id = info.id,
                name = info.name,
                createdAt = info.createdAt,
                updatedAt = info.updatedAt,
                deletedAt = info.deletedAt,
            )
        }
    }

    /**
     * 상세 (A-3). 목록과 다른 것은 **연결 상품 수**뿐이다 (D-10).
     *
     * 이 숫자가 0 이 아니면 브랜드를 지울 수 없다 (P-11). 거절의 이유를 상세가 먼저 보여준다.
     * 목록에 넣지 않은 이유는 브랜드 수만큼 세게 되기 때문이다.
     */
    data class BrandDetailResponse(
        val id: Long,
        val name: String,
        val productCount: Long,
        val createdAt: ZonedDateTime,
        val updatedAt: ZonedDateTime,
        val deletedAt: ZonedDateTime?,
    ) {
        companion object {
            fun from(info: BrandDetailInfo): BrandDetailResponse = BrandDetailResponse(
                id = info.brand.id,
                name = info.brand.name,
                productCount = info.productCount,
                createdAt = info.brand.createdAt,
                updatedAt = info.brand.updatedAt,
                deletedAt = info.brand.deletedAt,
            )
        }
    }
}
