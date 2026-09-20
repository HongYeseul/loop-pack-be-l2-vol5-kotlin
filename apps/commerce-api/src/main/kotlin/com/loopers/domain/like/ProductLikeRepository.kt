package com.loopers.domain.like

/**
 * **관계의 id 로 찾는 조회가 없다.** 좋아요는 언제나 (사용자, 상품) 쌍으로 지목된다 —
 * 요청에 들어오는 것이 그 둘이고, 관계의 id 를 아는 화면이 없다.
 */
interface ProductLikeRepository {
    /** 같은 쌍을 두 번 저장하면 `UNIQUE(user_id, product_id)` 가 거절한다 (P-14). 중복 판단은 부르는 쪽이 먼저 한다. */
    fun save(productLike: ProductLike): ProductLike

    fun exists(userId: Long, productId: Long): Boolean

    /** 없는 관계를 지워도 성공이다 (P-17). */
    fun delete(userId: Long, productId: Long)

    /** P-15 · 상품에 저장해 둔 숫자가 아니라 **관계에서 센다**. */
    fun countByProductId(productId: Long): Long

    /**
     * 목록 한 페이지의 좋아요 수를 **한 번에** 센다 (DS-1). 상품 수만큼 조회하면 20건에 20번이다.
     *
     * **좋아요가 없는 상품은 키가 없다.** 없는 것과 0 은 같은 뜻이라 부르는 쪽이 0 으로 읽는다.
     */
    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long>
}
