package com.loopers.like.domain

/** 관계 id 조회 없음, 항상 (사용자, 상품) 쌍으로 지목 */
interface ProductLikeRepository {
    /** 중복 저장은 UNIQUE 제약이 거절, 중복 판단은 호출부 선행 */
    fun save(productLike: ProductLike): ProductLike

    fun exists(userId: Long, productId: Long): Boolean

    /** 없는 관계 삭제도 성공 */
    fun delete(userId: Long, productId: Long)

    fun countByProductId(productId: Long): Long

    /** 좋아요 없는 상품은 키 없음, 호출부에서 0 으로 처리 */
    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long>
}
