package com.loopers.infrastructure.like

import com.loopers.domain.like.ProductLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 한 상품을 묻는 질문은 파생 쿼리로 둔다 — `ProductJpaRepository` 와 같다. */
interface ProductLikeJpaRepository : JpaRepository<ProductLike, Long> {
    fun findByUserIdAndProductId(userId: Long, productId: Long): ProductLike?

    fun existsByUserIdAndProductId(userId: Long, productId: Long): Boolean

    /** P-15 · 좋아요 수는 여기서 센다. 상품에 저장된 숫자가 아니다. */
    fun countByProductId(productId: Long): Long

    /**
     * 목록 한 페이지분을 한 번에 센다 (DS-1). 파생 쿼리로는 `GROUP BY` 를 적을 수 없다.
     *
     * 좋아요가 없는 상품은 결과에 줄이 없다 — `GROUP BY` 가 행이 있는 상품만 묶기 때문이다.
     */
    @Query("SELECT l.productId, COUNT(l) FROM ProductLike l WHERE l.productId IN :productIds GROUP BY l.productId")
    fun countGroupedPerProduct(@Param("productIds") productIds: Collection<Long>): List<Array<Any>>
}
