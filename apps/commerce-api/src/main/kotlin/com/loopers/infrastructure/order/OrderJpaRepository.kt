package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

/** 이름에 소유권 조건을 담아 확인 누락 방지 (findByIdAndUserId) */
interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByIdAndUserId(id: Long, userId: Long): Order?

    /** 같은 주문의 확정·취소가 동시에 DRAFT 로 판단하는 것 방지 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByIdAndUserId(id: Long, userId: Long): Order?

    /** 최신 주문순, 총 개수도 같은 조건에서 산출 */
    fun findAllByUserIdOrderByIdDesc(userId: Long, pageable: Pageable): Page<Order>
}
