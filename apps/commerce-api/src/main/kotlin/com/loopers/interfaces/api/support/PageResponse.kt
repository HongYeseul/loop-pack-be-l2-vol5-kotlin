package com.loopers.interfaces.api.support

import com.loopers.domain.support.PageResult

/**
 * 목록 응답의 공통 모양. 과제가 상품 목록에 **총 개수**를 함께 요구한다 (설계 6-2절 C-2).
 *
 * `PageResult`(domain)를 그대로 내보내지 않는 이유: 도메인 모델이 응답 계약이 되면
 * 도메인을 고칠 때마다 계약이 따라 바뀐다.
 */
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
