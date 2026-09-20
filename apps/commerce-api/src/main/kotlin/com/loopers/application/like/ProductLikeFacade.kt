package com.loopers.application.like

import com.loopers.domain.like.ProductLikeService
import com.loopers.domain.product.ProductService
import com.loopers.domain.user.LoginId
import com.loopers.domain.user.UserService
import org.springframework.stereotype.Component

/**
 * 좋아요는 **사용자와 상품에 걸친 관계**라 그 둘을 확인하는 자리가 여기다 (설계 2-2절 · 3절).
 * `ProductLikeService` 는 관계만 알고, 상품이 살아 있는지는 모른다.
 */
@Component
class ProductLikeFacade(
    private val productLikeService: ProductLikeService,
    private val productService: ProductService,
    private val userService: UserService,
) {
    /**
     * C-4 · 등록. **삭제된 상품에는 걸 수 없다** (P-16) — `getAliveOrThrow` 가 거절한다.
     *
     * 판매중지·단종은 거르지 않는다. 막는 것은 삭제뿐이고, 판매 상태는 다른 축이다 (DS-11).
     */
    fun like(loginId: LoginId, productId: Long): ProductLikeInfo {
        val user = userService.getActiveOrThrow(loginId)
        val product = productService.getAliveOrThrow(productId)
        productLikeService.like(userId = user.userId, productId = product.productId)
        return ProductLikeInfo(liked = true, likeCount = productLikeService.countByProductId(product.productId))
    }

    /**
     * C-5 · 취소. **상품이 살아 있는지 보지 않는다** (P-16) — 새로 거는 것은 막되
     * 이미 걸어둔 것은 거둘 수 있어야 한다. 확인하면 삭제된 상품의 관계가 영영 남는다.
     */
    fun unlike(loginId: LoginId, productId: Long): ProductLikeInfo {
        val user = userService.getActiveOrThrow(loginId)
        productLikeService.unlike(userId = user.userId, productId = productId)
        return ProductLikeInfo(liked = false, likeCount = productLikeService.countByProductId(productId))
    }
}
