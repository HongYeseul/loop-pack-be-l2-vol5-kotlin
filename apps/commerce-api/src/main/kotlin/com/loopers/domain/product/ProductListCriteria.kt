package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria

/** 삭제·판매중지·단종 제외는 findAliveProducts 이름에 담음 (호출부가 끌 수 없게) */
data class ProductListCriteria(
    /** null 이면 전체 브랜드 */
    val brandId: Long?,
    val sort: ProductSort,
    val page: PageCriteria,
)
