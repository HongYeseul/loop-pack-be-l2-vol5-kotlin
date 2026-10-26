package com.loopers.like.interfaces.api

import com.loopers.like.application.ProductLikeFacade
import com.loopers.product.application.ProductFacade
import com.loopers.support.domain.PageCriteria
import com.loopers.user.domain.LoginId
import com.loopers.support.interfaces.api.ApiResponse
import com.loopers.product.interfaces.api.ProductV1Dto
import com.loopers.support.interfaces.api.PageResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 등록·취소를 method 로 분리 (토글이면 요청 횟수에 따라 결과가 달라짐) */
@RestController
@RequestMapping("/api/v1")
class ProductLikeV1Controller(
    private val productLikeFacade: ProductLikeFacade,
    private val productFacade: ProductFacade,
) : ProductLikeV1ApiSpec {
    @PostMapping("/products/{productId}/likes")
    override fun like(
        @PathVariable(value = "productId") productId: Long,
        loginId: LoginId,
    ): ApiResponse<ProductLikeV1Dto.LikeResponse> =
        productLikeFacade.like(loginId, productId)
            .let { ProductLikeV1Dto.LikeResponse.from(it) }
            .let { ApiResponse.success(it) }

    /** 좋아요로 거른 상품 목록이라 조립은 ProductFacade 담당, 응답은 상품 목록과 동일 */
    @GetMapping("/users/{userId}/likes")
    override fun getLikedProducts(
        @PathVariable(value = "userId") userId: String,
        loginId: LoginId,
        @RequestParam(value = "page", required = false) page: Int?,
        @RequestParam(value = "size", required = false) size: Int?,
    ): ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> =
        productFacade.getLikedProducts(loginId = loginId, targetUserId = userId, page = PageCriteria.of(page, size))
            .let { PageResponse.from(it, ProductV1Dto.ProductResponse::from) }
            .let { ApiResponse.success(it) }

    @DeleteMapping("/products/{productId}/likes")
    override fun unlike(
        @PathVariable(value = "productId") productId: Long,
        loginId: LoginId,
    ): ApiResponse<ProductLikeV1Dto.LikeResponse> =
        productLikeFacade.unlike(loginId, productId)
            .let { ProductLikeV1Dto.LikeResponse.from(it) }
            .let { ApiResponse.success(it) }
}
