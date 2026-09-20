package com.loopers.application.point

/**
 * C-7 · C-8 의 결과 (P-22).
 *
 * 충전 응답과 조회 응답이 **같은 모양**입니다 — 둘 다 "지금 얼마인가" 에 답하고,
 * 충전은 그 값을 바꾼 뒤 답할 뿐입니다 (P-31 이 좋아요에서 고른 것과 같은 판단).
 */
data class PointInfo(val balance: Long)
