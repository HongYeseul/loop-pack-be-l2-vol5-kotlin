package com.loopers.domain.order

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

@Component
class OrderService(
    private val orderRepository: OrderRepository,
) {
    /** 품목 상품의 생존·판매 가능 여부는 OrderFacade 가 확인 (애그리게잇 경계) */
    @Transactional
    fun create(userId: Long, items: List<OrderItem>, now: ZonedDateTime): Order =
        orderRepository.save(Order(userId = userId, items = items, now = now))

    /** 만료 검사보다 먼저 상태로 걸러냄, 확정·취소 주문도 만료 시각은 지남 */
    @Transactional(readOnly = true)
    fun getDraftOwnedByOrThrow(orderId: Long, userId: Long): Order =
        getOwnedByOrThrow(orderId = orderId, userId = userId).also {
            if (!it.status.isDraft) {
                throw CoreException(
                    ErrorType.ORDER_NOT_DRAFT,
                    "[orderId = $orderId, status = ${it.status}] 확정 전(DRAFT) 주문이 아닙니다.",
                )
            }
        }

    @Transactional(readOnly = true)
    fun getOwnedByOrThrow(orderId: Long, userId: Long): Order =
        orderRepository.findOwnedBy(orderId = orderId, userId = userId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getIgnoringOwnerOrThrow(orderId: Long): Order =
        orderRepository.findIgnoringOwner(orderId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> =
        orderRepository.findOrdersOwnedBy(userId = userId, page = page)
}
