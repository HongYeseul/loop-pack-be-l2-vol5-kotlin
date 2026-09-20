package com.loopers.domain.order

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult

/**
 * **`findById` 를 두지 않는다.** 주문을 찾는 모든 질문에 "내 것인가" 가 따라붙으므로(P-02),
 * 조건을 이름이 들고 있어야 부르는 쪽이 소유권 확인을 빠뜨릴 수 없다 (DS-3 과 같은 이유).
 */
interface OrderRepository {
    fun save(order: Order): Order

    /** 없는 주문과 남의 주문을 **구분하지 않는다** (P-02). 부르는 쪽은 둘 다 `null` 로 받는다. */
    fun findOwnedBy(orderId: Long, userId: Long): Order?

    /**
     * C-11 · 내 주문 목록.
     *
     * **정렬은 계약이다** (D-3). **최신 주문순 = `id` 내림차순**이고(P-46) 가짜 저장소도 같은 순서를 지킨다.
     * `id` 하나로 정렬하므로 동점이 없다 — 보조 기준을 따로 붙일 자리가 없다 (P-09).
     *
     * **품목은 읽지 않는다.** 목록 응답은 요약이고(설계 6-2절 C-11) 품목은 단건 조회에서만 본다.
     */
    fun findOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order>
}
