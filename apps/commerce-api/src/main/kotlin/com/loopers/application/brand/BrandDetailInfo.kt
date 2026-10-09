package com.loopers.application.brand

/**
 * 관리자 브랜드 상세, 연결 상품 수 포함
 * BrandInfo 에 넣으면 목록이 브랜드 수만큼 집계하거나 0 으로 거짓 응답
 * productCount 는 삭제되지 않은 상품 기준 (재고 0·판매중지·단종 포함)
 */
data class BrandDetailInfo(
    val brand: BrandInfo,
    val productCount: Long,
)
