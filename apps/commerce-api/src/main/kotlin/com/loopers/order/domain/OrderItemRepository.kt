package com.loopers.order.domain

/** 주문 소유권 확인 없음, 확인을 마친 주문의 품목만 조회할 것 */
interface OrderItemRepository {
    fun saveAll(items: List<OrderItem>): List<OrderItem>

    /** 담은 순서(id 오름차순) */
    fun findAllByOrderId(orderId: Long): List<OrderItem>
}
