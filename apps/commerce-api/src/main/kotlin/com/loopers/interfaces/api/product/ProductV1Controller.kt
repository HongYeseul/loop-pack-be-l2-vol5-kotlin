package com.loopers.interfaces.api.product

import com.loopers.application.product.ProductFacade
import com.loopers.domain.product.ProductListCriteria
import com.loopers.domain.product.ProductSort
import com.loopers.domain.support.PageCriteria
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/products")
class ProductV1Controller(
    private val productFacade: ProductFacade,
) : ProductV1ApiSpec {
    /** 정렬·페이지 해석은 domain 값 타입(ProductSort, PageCriteria)에 위임, 규칙 중복 방지 */
    @GetMapping
    override fun getProducts(
        @RequestParam(value = "brandId", required = false) brandId: Long?,
        @RequestParam(value = "sort", required = false) sort: String?,
        @RequestParam(value = "page", required = false) page: Int?,
        @RequestParam(value = "size", required = false) size: Int?,
    ): ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> =
        ProductListCriteria(brandId = brandId, sort = ProductSort.from(sort), page = PageCriteria.of(page, size))
            .let { productFacade.getAll(it) }
            .let { PageResponse.from(it, ProductV1Dto.ProductResponse::from) }
            .let { ApiResponse.success(it) }

    @GetMapping("/{productId}")
    override fun getProduct(
        @PathVariable(value = "productId") productId: Long,
    ): ApiResponse<ProductV1Dto.ProductDetailResponse> =
        productFacade.get(productId)
            .let { ProductV1Dto.ProductDetailResponse.from(it) }
            .let { ApiResponse.success(it) }
}
