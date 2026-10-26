package com.loopers.support.domain

/** 자체 페이지 결과 타입, 저장 기술이 응답 계약에 새는 것 방지 */
data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
)
