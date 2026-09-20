package com.loopers.interfaces.api.admin.brand

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
}
