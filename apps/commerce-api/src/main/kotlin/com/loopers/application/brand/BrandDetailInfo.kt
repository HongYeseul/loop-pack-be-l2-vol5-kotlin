package com.loopers.application.brand

/**
 * 관리자 브랜드 상세 (A-3 · D-10). 브랜드에 **연결된 상품 수**를 함께 든다.
 *
 * `BrandInfo` 의 필드로 넣지 않은 이유: 세는 자리가 A-3 하나뿐이다. 넣으면 목록(A-1)이
 * 브랜드 수만큼 세거나 **0 으로 채워 "연결된 상품이 없다"고 거짓말을 한다.**
 *
 * DS-5 를 어기는 것은 아니다 — DS-5 가 나누지 말라고 한 것은 **보는 사람**이고,
 * 이것은 **함께 읽어온 것**을 묶은 것이다.
 *
 * @property productCount 삭제되지 않은 연결 상품 수. **재고 0 도 판매중지·단종도 센다** (P-11 · DS-11).
 *                        0 이 아니면 브랜드를 지울 수 없다 — `BRAND_HAS_PRODUCTS` 의 이유다.
 */
data class BrandDetailInfo(
    val brand: BrandInfo,
    val productCount: Long,
)
