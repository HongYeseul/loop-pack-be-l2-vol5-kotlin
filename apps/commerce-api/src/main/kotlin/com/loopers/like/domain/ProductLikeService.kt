package com.loopers.like.domain

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductLikeService(
    private val productLikeRepository: ProductLikeRepository,
) {
    /** 토글이 아닌 "좋아요 상태로 만들기", 반복 호출해도 결과 동일 */
    @Transactional
    fun like(userId: Long, productId: Long) {
        if (!productLikeRepository.exists(userId = userId, productId = productId)) {
            productLikeRepository.save(ProductLike(userId = userId, productId = productId))
        }
    }

    /** 없는 관계 취소도 성공, 이미 원하는 상태 */
    @Transactional
    fun unlike(userId: Long, productId: Long) {
        productLikeRepository.delete(userId = userId, productId = productId)
    }

    @Transactional(readOnly = true)
    fun countByProductId(productId: Long): Long = productLikeRepository.countByProductId(productId)

    @Transactional(readOnly = true)
    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> =
        productLikeRepository.countByProductIds(productIds)
}
