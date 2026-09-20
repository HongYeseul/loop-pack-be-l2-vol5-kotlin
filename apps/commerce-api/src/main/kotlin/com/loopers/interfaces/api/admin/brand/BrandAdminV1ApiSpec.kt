package com.loopers.interfaces.api.admin.brand

import com.loopers.domain.admin.AdminLoginId
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Brand Admin V1 API", description = "관리자용 브랜드 API 입니다. ROLE_ADMIN 만 통과합니다.")
interface BrandAdminV1ApiSpec {
    @Operation(summary = "브랜드 목록", description = "삭제된 브랜드도 함께 조회합니다. id 내림차순입니다.")
    fun getBrands(page: Int?, size: Int?, requester: AdminLoginId): ApiResponse<PageResponse<BrandAdminV1Dto.BrandResponse>>

    @Operation(summary = "브랜드 생성")
    fun createBrand(request: BrandAdminV1Dto.CreateRequest, requester: AdminLoginId): ApiResponse<BrandAdminV1Dto.BrandResponse>

    @Operation(
        summary = "브랜드 상세",
        description = "삭제된 브랜드도 조회합니다. 연결된 상품 수를 함께 줍니다 — 0 이 아니면 삭제할 수 없습니다 (P-11).",
    )
    fun getBrand(brandId: Long, requester: AdminLoginId): ApiResponse<BrandAdminV1Dto.BrandDetailResponse>

    @Operation(summary = "브랜드 수정", description = "삭제된 브랜드는 대상이 아닙니다 (P-12).")
    fun updateBrand(
        brandId: Long,
        request: BrandAdminV1Dto.UpdateRequest,
        requester: AdminLoginId,
    ): ApiResponse<BrandAdminV1Dto.BrandResponse>

    @Operation(
        summary = "브랜드 삭제",
        description = "논리 삭제입니다 (D-2). 살아 있는 상품이 연결되어 있으면 BRAND_HAS_PRODUCTS 로 거절합니다 (P-11).",
    )
    fun deleteBrand(brandId: Long, requester: AdminLoginId): ApiResponse<Any>
}
