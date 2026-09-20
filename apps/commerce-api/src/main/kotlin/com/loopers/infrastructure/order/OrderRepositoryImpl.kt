package com.loopers.infrastructure.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class OrderRepositoryImpl(
    private val orderJpaRepository: OrderJpaRepository,
) : OrderRepository {
    /** 품목은 `cascade` 로 함께 저장된다 (설계 2-2절) — 따로 저장할 길을 두지 않는다. */
    override fun save(order: Order): Order = orderJpaRepository.save(order)

    override fun findOwnedBy(orderId: Long, userId: Long): Order? =
        orderJpaRepository.findByIdAndUserId(id = orderId, userId = userId)

    /** 정렬을 메서드 이름이 든다 — `Sort` 로 넘기면 부르는 쪽마다 다른 순서를 줄 수 있다 (P-46 · D-3). */
    override fun findOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> {
        val found = orderJpaRepository.findAllByUserIdOrderByIdDesc(userId, PageRequest.of(page.page, page.size))
        return PageResult(items = found.content, page = page.page, size = page.size, totalCount = found.totalElements)
    }
}
