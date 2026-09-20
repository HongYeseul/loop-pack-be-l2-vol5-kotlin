package com.loopers.interfaces.api.admin.product

import com.loopers.application.product.ProductFacade
import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.support.PageCriteria
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/products")
class ProductAdminV1Controller(
    private val productFacade: ProductFacade,
) : ProductAdminV1ApiSpec {
    @GetMapping
    override fun getProducts(
        @RequestParam(value = "page", required = false) page: Int?,
        @RequestParam(value = "size", required = false) size: Int?,
        requester: AdminLoginId,
    ): ApiResponse<PageResponse<ProductAdminV1Dto.ProductResponse>> =
        productFacade.getAllForAdmin(requester, PageCriteria.of(page, size))
            .let { PageResponse.from(it, ProductAdminV1Dto.ProductResponse::from) }
            .let { ApiResponse.success(it) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun createProduct(
        @RequestBody request: ProductAdminV1Dto.CreateRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> =
        productFacade.create(
            requester = requester,
            brandId = request.brandId,
            name = request.name,
            price = request.price,
            stock = request.stock ?: 0,
        )
            .let { ProductAdminV1Dto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping("/{productId}")
    override fun getProduct(
        @PathVariable(value = "productId") productId: Long,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> =
        productFacade.getForAdmin(requester, productId)
            .let { ProductAdminV1Dto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }

    @PutMapping("/{productId}")
    override fun updateProduct(
        @PathVariable(value = "productId") productId: Long,
        @RequestBody request: ProductAdminV1Dto.UpdateRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> =
        productFacade.changeNameAndPrice(requester, productId, request.name, request.price)
            .let { ProductAdminV1Dto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }

    @DeleteMapping("/{productId}")
    override fun deleteProduct(
        @PathVariable(value = "productId") productId: Long,
        requester: AdminLoginId,
    ): ApiResponse<Any> {
        productFacade.delete(requester, productId)
        return ApiResponse.success()
    }

    @PutMapping("/{productId}/stock")
    override fun changeStock(
        @PathVariable(value = "productId") productId: Long,
        @RequestBody request: ProductAdminV1Dto.StockRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> =
        productFacade.changeStock(requester, productId, request.quantity)
            .let { ProductAdminV1Dto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }

    @PutMapping("/{productId}/status")
    override fun changeStatus(
        @PathVariable(value = "productId") productId: Long,
        @RequestBody request: ProductAdminV1Dto.StatusRequest,
        requester: AdminLoginId,
    ): ApiResponse<ProductAdminV1Dto.ProductResponse> =
        productFacade.changeStatus(requester, productId, request.status)
            .let { ProductAdminV1Dto.ProductResponse.from(it) }
            .let { ApiResponse.success(it) }
}
