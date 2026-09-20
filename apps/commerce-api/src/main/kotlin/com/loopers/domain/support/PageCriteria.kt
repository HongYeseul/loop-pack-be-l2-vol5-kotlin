package com.loopers.domain.support

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 목록 조회의 페이지 입력 (설계 6-1절 공통).
 *
 * 목록을 가진 API 가 여럿이라 한 곳에 둔다. 도메인마다 따로 적으면
 * 같은 `INVALID_PAGE` 가 도메인마다 다른 범위를 뜻하게 된다.
 */
data class PageCriteria(
    val page: Int,
    val size: Int,
) {
    init {
        if (page < 0) {
            throw CoreException(ErrorType.INVALID_PAGE, "페이지는 0 이상이어야 합니다.")
        }
        if (size !in 1..MAX_SIZE) {
            throw CoreException(ErrorType.INVALID_PAGE, "페이지 크기는 1 이상 ${MAX_SIZE} 이하여야 합니다.")
        }
    }

    /** 건너뛸 **행 수**. 페이지 수가 아니다 (기획 D-3). */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val MAX_SIZE = 100
        const val DEFAULT_SIZE = 20

        fun of(page: Int?, size: Int?): PageCriteria = PageCriteria(page = page ?: 0, size = size ?: DEFAULT_SIZE)
    }
}
