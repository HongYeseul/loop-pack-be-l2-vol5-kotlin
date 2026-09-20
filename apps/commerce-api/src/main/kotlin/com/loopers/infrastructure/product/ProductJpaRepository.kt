package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 한 테이블로 답할 수 있는 질문은 파생 쿼리로 둔다.
 *
 * **총 개수가 목록과 같은 조건에서 나온다.** `Page.totalElements` 가 조건을 그대로 물려받으므로
 * "총 10건이라는데 페이지를 넘기면 8건" 이 되는 어긋남이 생길 자리가 없다.
 * 조건을 손으로 두 번 적으면 그때부터 두 벌을 맞춰야 한다.
 */
interface ProductJpaRepository : JpaRepository<Product, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Product?

    fun findAllByOrderByIdDesc(pageable: Pageable): Page<Product>

    /**
     * 고객 목록 (C-2). 이름이 든 두 조건이 P-12(삭제 제외)와 P-39(판매중만)다.
     *
     * 정렬은 `pageable` 이 나른다 — 정렬 종류마다 메서드를 만들면 P-08 의 정렬이 늘 때마다
     * 메서드가 늘고, 보조 기준 `id DESC`(P-09)를 한 군데만 빠뜨릴 수 있다.
     */
    fun findAllByDeletedAtIsNullAndStatus(status: ProductStatus, pageable: Pageable): Page<Product>

    fun findAllByDeletedAtIsNullAndStatusAndBrandId(status: ProductStatus, brandId: Long, pageable: Pageable): Page<Product>

    /**
     * C-2 · 좋아요 많은 순 (P-08).
     *
     * **집계값이라 `Sort` 로 표현되지 않는다** (DS-1). 정렬 기준이 `product` 에 없는 값이라
     * 서브쿼리로 세면서 정렬한다. 동점이면 `id` 내림차순이 붙는 것은 다른 정렬과 같다 (P-09).
     *
     * 브랜드 필터를 메서드로 나누지 않고 조건에 둔다 — 나누면 이 `ORDER BY` 가 두 벌이 된다.
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

    /**
     * C-6 · 내가 좋아요한, 살아 있는 상품 (P-45 · P-16).
     *
     * **여기만 JPQL 이다.** 거르는 조건(`product_like` 의 사용자)과 정렬 기준(관계의 id)이
     * 상품 테이블에 없어 파생 쿼리로 적을 수 없다. 관계를 먼저 읽고 상품을 거르면
     * 지워진 상품만큼 페이지가 줄어, `size=20` 에 18건이 오고 총 개수도 어긋난다.
     *
     * 조건은 여기 한 번만 적는다 — 총 개수는 Spring Data 가 이 쿼리에서 만든다.
     */
    @Query(
        """
        SELECT p FROM ProductLike l, Product p
        WHERE p.id = l.productId AND l.userId = :userId AND p.deletedAt IS NULL
        ORDER BY l.id DESC
        """,
    )
    fun findLikedProducts(@Param("userId") userId: Long, pageable: Pageable): Page<Product>

    /** P-11 · 재고와 판매 상태는 보지 않는다. 삭제되지 않았으면 연결이다 (DS-11). */
    fun existsByBrandIdAndDeletedAtIsNull(brandId: Long): Boolean

    fun countByBrandIdAndDeletedAtIsNull(brandId: Long): Long
}
