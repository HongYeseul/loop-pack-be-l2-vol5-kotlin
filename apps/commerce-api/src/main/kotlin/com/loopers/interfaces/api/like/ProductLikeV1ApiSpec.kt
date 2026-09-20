package com.loopers.interfaces.api.like

import com.loopers.domain.user.LoginId
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.product.ProductV1Dto
import com.loopers.interfaces.api.support.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Product Like V1 API", description = "고객용 상품 좋아요 API 입니다.")
interface ProductLikeV1ApiSpec {
    @Operation(
        summary = "좋아요 등록",
        description = "몇 번을 보내도 결과가 같습니다. 삭제된 상품은 PRODUCT_NOT_FOUND 로 응답합니다.",
    )
    fun like(
        @Schema(name = "상품 ID", description = "좋아요할 상품의 ID")
        productId: Long,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<ProductLikeV1Dto.LikeResponse>

    @Operation(
        summary = "좋아요 취소",
        description = "걸려 있지 않아도 성공입니다. 상품이 삭제되었어도 이미 걸어둔 관계는 취소됩니다.",
    )
    fun unlike(
        @Schema(name = "상품 ID", description = "좋아요를 취소할 상품의 ID")
        productId: Long,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<ProductLikeV1Dto.LikeResponse>

    @Operation(
        summary = "내 좋아요 목록 조회",
        description = "최근에 좋아요한 순이고 삭제된 상품만 빠집니다 (P-45). 남의 목록은 USER_NOT_FOUND 로 응답합니다.",
    )
    fun getLikedProducts(
        @Schema(name = "사용자 ID", description = "X-USER-ID 헤더와 같아야 합니다.")
        userId: String,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
        @Schema(name = "페이지", description = "0부터 시작합니다.")
        page: Int?,
        @Schema(name = "페이지 크기", description = "1 이상 100 이하입니다.")
        size: Int?,
    ): ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>
}
