package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult

/**
 * **`findById` 를 두지 않는다** (DS-3). 모든 조회에 "삭제되지 않음" 이 따라붙는데(P-12)
 * 조건을 이름이 들고 있으면 부르는 쪽이 **어느 질문인지 고르지 않을 수 없다**.
 */
interface ProductRepository {
    fun save(product: Product): Product

    /**
     * 고객의 질문 — 삭제되지 않은 것만 (P-04).
     *
     * **판매중지·단종은 거르지 않는다.** 상세로는 볼 수 있고 목록에서만 빠지므로,
     * 거르는 자리는 [findAliveProducts] 다 (P-39).
     */
    fun findAlive(id: Long): Product?

    /**
     * 주문 품목의 상품을 **한 번에** 읽는다 (C-9 · C-10).
     *
     * 삭제된 것과 없는 것은 조용히 빠진다 — 몇 개가 돌아왔는지로 판단하는 것은 부르는 쪽이다.
     * 저장소가 거절하면 "재고 0 과 같이 본다"(D-8)는 판단이 저장소에 들어간다.
     */
    fun findAliveAll(ids: Collection<Long>): List<Product>

    /** 관리자의 질문 — 삭제된 것도 본다 (P-33). */
    fun findIncludingDeleted(id: Long): Product?

    /**
     * 고객 목록 (C-2). 삭제된 것과 **판매중지·단종을 뺀다** (P-12 · P-39).
     *
     * **정렬은 계약이다** (D-3). `criteria.sort` 로 정렬한 뒤 **언제나 `id` 내림차순을 붙여야 한다**
     * (P-09) — 안 붙이면 동점 구간에서 페이지가 겹치거나 빠진다. 가짜 저장소도 같은 순서를 지킨다.
     *
     * **브랜드 이름과 좋아요 수는 여기서 읽지 않는다** (DS-1). `application` 이 한 페이지
     * 분량으로 한 번에 읽어 붙인다.
     */
    fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product>

    /**
     * C-6 · 내가 좋아요한 상품.
     *
     * **삭제된 상품만 뺀다** (P-45 · P-16). 판매중지·단종은 남는다 — P-39 가 거르라고 한 "고객 목록"은
     * 카탈로그([findAliveProducts])이고, 내 좋아요는 카탈로그가 아니라 **내 기록**이다.
     *
     * **정렬은 최근에 좋아요한 순이다** (P-45 · D-3 계약). 상품이 언제 등록됐는지가 아니라 내가 누른 순서다.
     */
    fun findAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product>

    /** 관리자 목록 (A-6). 삭제된 것도 포함하고 **`id` 내림차순**이다 — A-1 과 같은 계약이다 (P-33 · D-3). */
    fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Product>

    /**
     * 이 브랜드에 살아 있는 상품이 하나라도 있나 (P-11).
     *
     * **"몇 개인가"가 아니라 "있냐 없냐"다.** 브랜드 삭제는 개수를 몰라도 답할 수 있고 더 싸다.
     * 개수가 필요한 자리는 A-3 하나뿐이라 [countAliveByBrandId] 로 나눠 둔다.
     *
     * **재고 0 도, 판매중지·단종도 센다.** 삭제되지 않았으면 연결이다 (DS-11).
     */
    fun existsAliveByBrandId(brandId: Long): Boolean

    /** 관리자 브랜드 상세의 "연결 상품 수" (A-3 · D-10). 세는 기준은 [existsAliveByBrandId] 와 같다. */
    fun countAliveByBrandId(brandId: Long): Long
}
