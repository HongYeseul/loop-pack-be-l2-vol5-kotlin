package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 단일 테이블 질문은 파생 쿼리, 총 개수가 목록과 같은 조건에서 나와 어긋남 없음 */
interface ProductJpaRepository : JpaRepository<Product, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Product?

    fun findAllByOrderByIdDesc(pageable: Pageable): Page<Product>

    fun findAllByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<Product>

    /** 정렬은 pageable 로 전달 (정렬 종류마다 메서드를 만들면 보조 기준 id DESC 누락 위험) */
    fun findAllByDeletedAtIsNullAndStatus(status: ProductStatus, pageable: Pageable): Page<Product>

    fun findAllByDeletedAtIsNullAndStatusAndBrandId(status: ProductStatus, brandId: Long, pageable: Pageable): Page<Product>

    /**
     * 집계값 정렬이라 Sort 로 표현 불가, 서브쿼리로 집계하며 정렬
     * 브랜드 필터는 조건으로 처리 (메서드를 나누면 ORDER BY 중복)
     */
    @Query(
        """
        SELECT p FROM Product p
        WHERE p.deletedAt IS NULL
          AND p.status = :status
          AND (:brandId IS NULL OR p.brandId = :brandId)
        ORDER BY (SELECT COUNT(l) FROM ProductLike l WHERE l.productId = p.id) DESC, p.id DESC
        """,
    )
    fun findAliveOnSaleWithMostLikes(
        @Param("status") status: ProductStatus,
        @Param("brandId") brandId: Long?,
        pageable: Pageable,
    ): Page<Product>

    /** 이 쿼리만 JPQL: 거르는 조건과 정렬 기준이 상품 테이블에 없어 파생 쿼리 불가 */
    @Query(
        """
        SELECT p FROM ProductLike l, Product p
        WHERE p.id = l.productId AND l.userId = :userId AND p.deletedAt IS NULL
        ORDER BY l.id DESC
        """,
    )
    fun findLikedProducts(@Param("userId") userId: Long, pageable: Pageable): Page<Product>

    /** 재고·판매 상태는 보지 않음 */
    fun existsByBrandIdAndDeletedAtIsNull(brandId: Long): Boolean

    fun countByBrandIdAndDeletedAtIsNull(brandId: Long): Long
}
