package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 파생 쿼리만 쓴다. SQL 문자열을 적지 않는다.
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

    /** P-11 · 재고와 판매 상태는 보지 않는다. 삭제되지 않았으면 연결이다 (DS-11). */
    fun existsByBrandIdAndDeletedAtIsNull(brandId: Long): Boolean

    fun countByBrandIdAndDeletedAtIsNull(brandId: Long): Long
}
