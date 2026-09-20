package com.loopers.interfaces.api.admin.product

import com.loopers.domain.admin.AdminLoginId
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Product Admin V1 API", description = "관리자용 상품 API 입니다. ROLE_ADMIN 만 통과합니다.")
interface ProductAdminV1ApiSpec {
    @Operation(summary = "상품 목록", description = "삭제된 상품도 함께 조회합니다. id 내림차순입니다.")
    fun getProducts(page: Int?, size: Int?, requester: AdminLoginId): ApiResponse<PageResponse<ProductAdminV1Dto.ProductResponse>>

    @Operation(summary = "상품 생성", description = "삭제되지 않은 브랜드에만 만들 수 있습니다 (P-05).")
    fun createProduct(
        request: ProductAdminV1Dto.CreateRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse>

    @Operation(summary = "상품 상세", description = "삭제된 상품도 조회합니다. 재고 수량과 삭제 시각을 포함합니다.")
    fun getProduct(productId: Long, requester: AdminLoginId): ApiResponse<ProductAdminV1Dto.ProductResponse>

    @Operation(summary = "상품 수정", description = "이름과 가격만 바꿉니다. 브랜드는 바꿀 수 없습니다 (P-05).")
    fun updateProduct(
        productId: Long,
        request: ProductAdminV1Dto.UpdateRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse>

    @Operation(summary = "상품 삭제", description = "논리 삭제입니다. 행은 남습니다 (D-2).")
    fun deleteProduct(productId: Long, requester: AdminLoginId): ApiResponse<Any>

    @Operation(summary = "재고 설정", description = "증감이 아니라 최종 수량입니다 (P-07). 두 번 보내도 결과가 같습니다.")
    fun changeStock(
        productId: Long,
        request: ProductAdminV1Dto.StockRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse>

    @Operation(
        summary = "판매 상태 설정",
        description = "ON_SALE · SUSPENDED · DISCONTINUED 중 하나입니다. 단종은 되돌릴 수 없습니다 (P-36).",
    )
    fun changeStatus(
        productId: Long,
        request: ProductAdminV1Dto.StatusRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse>
}
