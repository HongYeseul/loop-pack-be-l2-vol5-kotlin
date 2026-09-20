package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.ZonedDateTime

interface OrderJpaRepository : JpaRepository<Order, Long> {
    /**
     * DS-4 · 한 문장이다. 행을 읽어 하나씩 바꾸지 않아 `idx_orders_status_expires` 를 그대로 탄다.
     *
     * 상태를 함께 보지 않으면 **확정·취소된 주문까지 만료된다** — 그쪽도 만료 시각은 지나 있다.
     */
    @Modifying
    @Query(
        """
        UPDATE Order o SET o.status = 'EXPIRED'
        WHERE o.status = 'DRAFT' AND o.expiresAt < :now
        """,
    )
    fun expireDrafts(@Param("now") now: ZonedDateTime): Int
}
