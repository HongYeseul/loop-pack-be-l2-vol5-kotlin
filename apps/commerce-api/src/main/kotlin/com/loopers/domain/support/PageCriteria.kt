package com.loopers.domain.support

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/** 목록 공통 페이지 입력, 도메인별로 INVALID_PAGE 범위가 갈라지는 것 방지 */
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

    /** 건너뛸 행 수 (페이지 수 아님) */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val MAX_SIZE = 100
        const val DEFAULT_SIZE = 20

        fun of(page: Int?, size: Int?): PageCriteria = PageCriteria(page = page ?: 0, size = size ?: DEFAULT_SIZE)
    }
}
