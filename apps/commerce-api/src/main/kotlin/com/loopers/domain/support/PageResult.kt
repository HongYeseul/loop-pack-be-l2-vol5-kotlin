package com.loopers.domain.support

/**
 * 목록 조회의 결과. 과제가 상품 목록에 **총 개수**를 함께 요구한다 (설계 6-2절 C-2).
 *
 * 스프링의 `Page` 를 쓰지 않는 이유: `domain` 이 스프링 데이터 타입을 알게 되고,
 * 그 타입이 `application` 을 지나 응답까지 새면 저장 기술이 계약에 섞인다.
 */
data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
)
