package com.loopers.order.infrastructure

import com.loopers.order.domain.Order
import com.loopers.order.domain.OrderRepository
import com.loopers.support.domain.PageCriteria
import com.loopers.support.domain.PageResult
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class OrderRepositoryImpl(
    private val orderJpaRepository: OrderJpaRepository,
) : OrderRepository {
    override fun save(order: Order): Order = orderJpaRepository.save(order)

    override fun findOwnedBy(orderId: Long, userId: Long): Order? =
        orderJpaRepository.findByIdAndUserId(id = orderId, userId = userId)

    override fun findOwnedByForUpdate(orderId: Long, userId: Long): Order? =
        orderJpaRepository.findForUpdateByIdAndUserId(id = orderId, userId = userId)

    override fun findIgnoringOwner(orderId: Long): Order? = orderJpaRepository.findByIdOrNull(orderId)

    /** 정렬을 메서드 이름에 고정 (Sort 로 받으면 호출부마다 순서가 달라질 수 있음) */
    override fun findOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> {
        val found = orderJpaRepository.findAllByUserIdOrderByIdDesc(userId, PageRequest.of(page.page, page.size))
        return PageResult(items = found.content, page = page.page, size = page.size, totalCount = found.totalElements)
    }
}
