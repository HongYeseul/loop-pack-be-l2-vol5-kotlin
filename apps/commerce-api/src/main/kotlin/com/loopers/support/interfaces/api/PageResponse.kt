package com.loopers.support.interfaces.api

import com.loopers.support.domain.PageResult

/** 목록 공통 응답, domain 의 PageResult 를 그대로 노출하지 않음 (도메인 변경이 응답 계약에 전파) */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
) {
    companion object {
        fun <S, T> from(result: PageResult<S>, map: (S) -> T): PageResponse<T> = PageResponse(
            items = result.items.map(map),
            page = result.page,
            size = result.size,
            totalCount = result.totalCount,
        )
    }
}
