package com.loopers.like.application

/** 요청과 무관하게 현재 상태를 반환, 화면이 스스로 상태를 교정 */
data class ProductLikeInfo(
    val liked: Boolean,
    val likeCount: Long,
)
