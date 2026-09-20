package com.loopers.application.like

/**
 * 좋아요 등록·취소의 결과 (P-31 · D-9).
 *
 * **요청이 무엇이었든 지금 상태를 담는다.** 화면이 자기 상태를 스스로 교정할 수 있어야
 * "눌렀는데 반영이 늦게 뜨는" 문제가 응답 하나로 끝난다.
 */
data class ProductLikeInfo(
    val liked: Boolean,
    val likeCount: Long,
)
