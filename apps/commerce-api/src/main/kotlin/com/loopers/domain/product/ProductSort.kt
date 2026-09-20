package com.loopers.domain.product

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 상품 목록의 정렬 기준 (P-08).
 *
 * **어느 정렬이든 마지막에 `id` 내림차순이 붙는다** (P-09 · D-3). 동점이 있으면 DB 가 페이지마다
 * 다른 순서를 볼 수 있어, 어떤 항목은 두 페이지에 나오고 어떤 항목은 한 번도 안 나온다.
 * 그 보장은 이 enum 을 받는 저장소의 계약이다.
 */
enum class ProductSort(val value: String) {
    /** 최신순. 기본값이다. */
    LATEST("latest"),

    /** 가격 낮은 순. */
    PRICE_ASC("price_asc"),

    /** 좋아요 많은 순. 저장된 숫자가 아니라 **관계를 센 값**으로 정렬한다 (P-15 · DS-1). */
    LIKES_DESC("likes_desc"),
    ;

    companion object {
        val DEFAULT = LATEST

        /**
         * 값이 없으면 기본값, 규격 밖이면 거절한다 (P-08).
         * **조용히 기본값으로 되돌리지 않는다** — 요청자가 자기 정렬이 무시된 것을 끝내 모른다.
         */
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
