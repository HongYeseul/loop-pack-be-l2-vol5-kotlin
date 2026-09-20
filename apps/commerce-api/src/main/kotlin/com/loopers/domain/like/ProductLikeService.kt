package com.loopers.domain.like

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductLikeService(
    private val productLikeRepository: ProductLikeRepository,
) {
    /**
     * C-4 · "좋아요 **상태로 만들어줘**" 다 (D-9). 토글이 아니라서 몇 번 보내도 결과가 같다 (P-14).
     *
     * **상품이 살아 있는지는 여기서 보지 않는다** (P-16). 두 애그리게잇에 걸친 질문이라
     * `ProductLikeFacade` 가 확인한다 — `ProductService.create` 가 브랜드를 보지 않는 것과 같다 (P-05).
     *
     * 확인하고 저장하는 사이는 동시성에 열려 있다. 그때 막는 것은 `UNIQUE` 제약이고,
     * 그 경합을 어떻게 다룰지는 이번 범위 밖이다 (기획 Q-2).
     */
    @Transactional
    fun like(userId: Long, productId: Long) {
        if (!productLikeRepository.exists(userId = userId, productId = productId)) {
            productLikeRepository.save(ProductLike(userId = userId, productId = productId))
        }
    }

    /**
     * C-5 · 행을 지운다 (P-17 · D-2). 없는 관계를 취소해도 성공이다 —
     * 요청자가 원한 상태("좋아요 아님")가 이미 그러하므로 알려줄 잘못이 없다.
     */
    @Transactional
    fun unlike(userId: Long, productId: Long) {
        productLikeRepository.delete(userId = userId, productId = productId)
    }

    /** P-15 · 상품 하나의 좋아요 수. */
    @Transactional(readOnly = true)
    fun countByProductId(productId: Long): Long = productLikeRepository.countByProductId(productId)

    /** P-15 · 목록 한 페이지분을 한 번에. 좋아요가 없는 상품은 키가 없다. */
    @Transactional(readOnly = true)
    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> =
        productLikeRepository.countByProductIds(productIds)
}
