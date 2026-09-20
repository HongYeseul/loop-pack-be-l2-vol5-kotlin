package com.loopers.interfaces.api.like

import com.loopers.application.like.ProductLikeFacade
import com.loopers.application.product.ProductFacade
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.user.LoginId
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.product.ProductV1Dto
import com.loopers.interfaces.api.support.PageResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 등록과 취소를 **method 로 나눈다** (D-9). 토글 하나였다면 결과가 요청 횟수에 달리고,
 * 요청이 한 번 더 가거나 덜 갔을 때 화면과 서버가 갈라진다.
 *
 * `loginId` 는 `UserIdArgumentResolver` 가 `X-USER-ID` 헤더에서 만든다 (P-01).
 */
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

    /**
     * C-6 은 좋아요로 거른 **상품 목록**이라 조립을 `ProductFacade` 가 한다 —
     * 응답도 C-2 와 같은 `ProductResponse` 이고, 조립을 여기서 또 만들면 두 벌이 갈라진다 (DS-1).
     *
     * 경로의 `{userId}` 는 헤더와 같은 종류, 즉 `login_id` 문자열이다 (DS-9).
     */
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
