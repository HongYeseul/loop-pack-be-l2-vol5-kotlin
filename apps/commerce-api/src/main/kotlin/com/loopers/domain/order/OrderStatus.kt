package com.loopers.domain.order

/**
 * 주문의 상태 (기획 4-1절 · D-7).
 *
 * 갈 수 있는 길이 DRAFT 에서 나가는 셋뿐이라 전이 표를 두지 않는다 —
 * `Order` 가 "DRAFT 인가" 하나만 물으면 된다 ([ProductStatus] 와 갈리는 자리다).
 *
 * CANCELED 와 EXPIRED 를 합치지 않는 이유: 고객이 거둔 것과 시간이 지나 사라진 것은
 * **CS 응대가 다르다.** 한 상태로 합치면 그 구분을 복원할 수 없다 (D-7).
 */
enum class OrderStatus {
    /** 만들어졌지만 아직 결제하지 않은 상태. 재고도 포인트도 줄지 않았다 (P-23 · D-13). */
    DRAFT,

    /** 확정. 재고와 포인트가 차감됐다 (P-26). */
    CONFIRMED,

    /** 고객이 거둠 (P-29). */
    CANCELED,

    /** 10분이 지나 사라짐 (P-32). */
    EXPIRED,
    ;

    val isDraft: Boolean get() = this == DRAFT
}
