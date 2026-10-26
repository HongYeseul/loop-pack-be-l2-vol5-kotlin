package com.loopers.brand.interfaces.api.admin

import com.loopers.brand.application.BrandDetailInfo
import com.loopers.brand.application.BrandInfo
import java.time.ZonedDateTime

class BrandAdminV1Dto {
    data class CreateRequest(val name: String)

    data class UpdateRequest(val name: String)

    /** 관리자용 브랜드, 삭제 시각 포함 */
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

    /** 목록과 달리 연결 상품 수 포함, 목록에 넣으면 브랜드 수만큼 집계 */
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
