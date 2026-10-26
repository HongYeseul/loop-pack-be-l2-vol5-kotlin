package com.loopers.like.application

import com.loopers.like.domain.ProductLikeService
import com.loopers.product.domain.ProductService
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.UserService
import org.springframework.stereotype.Component

/** 사용자·상품에 걸친 관계라 두 대상의 확인은 여기서, ProductLikeService 는 관계만 담당 */
@Component
class ProductLikeFacade(
    private val productLikeService: ProductLikeService,
    private val productService: ProductService,
    private val userService: UserService,
) {
    /** 삭제된 상품에는 등록 불가, 판매중지·단종은 허용 */
    fun like(loginId: LoginId, productId: Long): ProductLikeInfo {
        val user = userService.getActiveOrThrow(loginId)
        val product = productService.getAliveOrThrow(productId)
        productLikeService.like(userId = user.userId, productId = product.productId)
        return ProductLikeInfo(liked = true, likeCount = productLikeService.countByProductId(product.productId))
    }

    /** 상품 생존 확인 없이 취소 허용 (확인하면 삭제된 상품의 관계가 영구히 남음) */
    fun unlike(loginId: LoginId, productId: Long): ProductLikeInfo {
        val user = userService.getActiveOrThrow(loginId)
        productLikeService.unlike(userId = user.userId, productId = productId)
        return ProductLikeInfo(liked = false, likeCount = productLikeService.countByProductId(productId))
    }
}
