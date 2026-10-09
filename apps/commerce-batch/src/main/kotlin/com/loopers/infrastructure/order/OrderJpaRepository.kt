package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.ZonedDateTime

interface OrderJpaRepository : JpaRepository<Order, Long> {
    /**
     * 행을 읽지 않고 한 문장으로 변경 (idx_orders_status_expires 사용)
     * 상태 조건 필수, 없으면 확정·취소 주문까지 만료됨
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
