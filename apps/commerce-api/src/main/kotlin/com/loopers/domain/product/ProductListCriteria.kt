package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria

/**
 * 고객 상품 목록의 조회 조건 (C-2 · P-08).
 *
 * **P-39(판매중지·단종 제외)와 P-12(삭제 제외)가 조건에 없다.** 조건으로 두면 부르는 쪽이
 * 그것을 끄는 선택을 하게 된다 — 두 조건은 `findAliveProducts` 라는 이름이 들고 있다 (DS-3).
 */
data class ProductListCriteria(
    /** 없으면 전체 브랜드. */
    val brandId: Long?,
    val sort: ProductSort,
    val page: PageCriteria,
)
