package com.loopers.infrastructure.like

import com.loopers.domain.like.ProductLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductLikeJpaRepository : JpaRepository<ProductLike, Long> {
    fun findByUserIdAndProductId(userId: Long, productId: Long): ProductLike?

    fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean

    fun countByProductId(productId: Long): Long

    /** 한 페이지분을 한 번에 집계 (파생 쿼리는 GROUP BY 불가), 좋아요 없는 상품은 결과에 없음 */
    @Query("SELECT l.productId, COUNT(l) FROM ProductLike l WHERE l.productId IN :productIds GROUP BY l.productId")
    fun countGroupedPerProduct(@Param("productIds") productIds: Collection<Long>): List<Array<Any>>
}
