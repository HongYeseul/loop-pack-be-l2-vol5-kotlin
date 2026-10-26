package com.loopers.product.domain

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/** 모든 정렬 끝에 id 내림차순 추가 (동점 시 페이지 중복·누락 방지), 저장소 계약 */
enum class ProductSort(val value: String) {
    /** 기본값 */
    LATEST("latest"),

    PRICE_ASC("price_asc"),

    /** 저장된 숫자가 아니라 관계 집계값으로 정렬 */
    LIKES_DESC("likes_desc"),
    ;

    companion object {
        val DEFAULT = LATEST

        /** 값 없으면 기본값, 규격 밖은 거절 (기본값으로 대체하면 무시된 줄 모름) */
        fun from(raw: String?): ProductSort {
            if (raw == null) return DEFAULT
            return entries.find { it.value == raw }
                ?: throw CoreException(
                    ErrorType.INVALID_SORT,
                    "[sort = $raw] 지원하는 정렬은 ${entries.map { it.value }} 입니다.",
                )
        }
    }
}
