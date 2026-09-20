package com.loopers.interfaces.api.product

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Product V1 API", description = "고객용 상품 API 입니다.")
interface ProductV1ApiSpec {
    @Operation(
        summary = "상품 목록 조회",
        description = "삭제·판매중지·단종된 상품은 목록에서 제외합니다. 정렬 값이나 페이지가 규격을 벗어나면 거절합니다.",
    )
    fun getProducts(
        @Schema(name = "브랜드 ID", description = "이 브랜드의 상품만 조회합니다. 없으면 전체입니다.")
        brandId: Long?,
        @Schema(name = "정렬", description = "latest · price_asc · likes_desc. 기본값은 latest 입니다.")
        sort: String?,
        @Schema(name = "페이지", description = "0부터 시작합니다.")
        page: Int?,
        @Schema(name = "페이지 크기", description = "1 이상 100 이하입니다.")
        size: Int?,
    ): ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>

    @Operation(
        summary = "상품 상세 조회",
        description = "판매중지·단종된 상품도 조회됩니다. 삭제되었거나 없는 상품은 PRODUCT_NOT_FOUND 로 응답합니다.",
    )
    fun getProduct(
        @Schema(name = "상품 ID", description = "조회할 상품의 ID")
        productId: Long,
    ): ApiResponse<ProductV1Dto.ProductDetailResponse>
}
