package com.loopers.interfaces.api.brand

import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Brand V1 API", description = "고객용 브랜드 API 입니다.")
interface BrandV1ApiSpec {
    @Operation(
        summary = "브랜드 상세 조회",
        description = "삭제되지 않은 브랜드 하나를 조회합니다. 없거나 삭제된 브랜드는 BRAND_NOT_FOUND 로 응답합니다.",
    )
    fun getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        brandId: Long,
    ): ApiResponse<BrandV1Dto.BrandResponse>
}
