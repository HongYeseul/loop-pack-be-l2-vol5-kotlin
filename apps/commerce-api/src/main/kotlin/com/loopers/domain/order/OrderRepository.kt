package com.loopers.domain.order

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult

/** findById 없음, 소유권 확인을 메서드 이름에 담아 누락 방지 */
interface OrderRepository {
    fun save(order: Order): Order

    /** 없는 주문과 남의 주문을 구분하지 않음 (둘 다 null) */
    fun findOwnedBy(orderId: Long, userId: Long): Order?

    /** 행을 잠그고 조회, 트랜잭션이 끝날 때까지 같은 주문의 다른 요청이 기다림 */
    fun findOwnedByForUpdate(orderId: Long, userId: Long): Order?

    /** 관리자용, 소유권 확인 없음 */
    fun findIgnoringOwner(orderId: Long): Order?

    /** 최신 주문순 = id 내림차순 (동점 없음), 품목은 조회하지 않음 */
    fun findOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order>
}
