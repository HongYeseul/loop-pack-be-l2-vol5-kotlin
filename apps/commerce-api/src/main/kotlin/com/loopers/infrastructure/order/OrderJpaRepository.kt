package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 조건과 정렬이 모두 `orders` 안에 있어 **전부 파생 쿼리다** (C-6 과 갈리는 자리 — DS-1 정정).
 *
 * 이름이 조건을 든다. `findById` 가 아니라 `findByIdAndUserId` 라서 소유권 확인을 빠뜨릴 수 없다 (P-02).
 */
interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByIdAndUserId(id: Long, userId: Long): Order?

    /** C-11 · 최신 주문순 (P-46). 총 개수도 같은 조건에서 나온다. */
    fun findAllByUserIdOrderByIdDesc(userId: Long, pageable: Pageable): Page<Order>
}
