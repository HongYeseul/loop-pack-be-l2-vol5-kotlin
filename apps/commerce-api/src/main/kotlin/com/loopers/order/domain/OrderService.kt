package com.loopers.order.domain

import com.loopers.support.domain.PageCriteria
import com.loopers.support.domain.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

@Component
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val stockReservationRepository: StockReservationRepository,
) {
    /** 품목 상품의 생존·판매 가능 여부는 OrderFacade 가 확인 (애그리게잇 경계) */
    @Transactional
    fun create(userId: Long, lines: List<OrderLine>, now: ZonedDateTime): Order {
        val order = orderRepository.save(Order(userId = userId, lines = lines, now = now))
        orderItemRepository.saveAll(lines.map { OrderItem(orderId = order.orderId, line = it) })
        return order
    }

    /** 만료 검사보다 먼저 상태로 걸러냄, 확정·취소 주문도 만료 시각은 지남 */
    @Transactional
    fun getDraftOwnedForUpdateOrThrow(orderId: Long, userId: Long): Order {
        val order = orderRepository.findOwnedByForUpdate(orderId = orderId, userId = userId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")
        order.validateDraft()
        return order
    }

    /** 상태 판단은 호출부 담당, 결제 시도 재요청은 DRAFT 가 아니어도 들어옴 */
    @Transactional
    fun getOwnedForUpdateOrThrow(orderId: Long, userId: Long): Order =
        orderRepository.findOwnedByForUpdate(orderId = orderId, userId = userId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    /** 소유권 확인 없음, 결제 시도로 주문을 찾은 경우만 */
    @Transactional
    fun getForUpdateIgnoringOwnerOrThrow(orderId: Long): Order =
        orderRepository.findIgnoringOwnerForUpdate(orderId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    @Transactional
    fun reserveStock(orderId: Long): StockReservation = stockReservationRepository.save(StockReservation(orderId = orderId))

    @Transactional
    fun consumeReservation(orderId: Long) = getReservationOrThrow(orderId).consume()

    @Transactional
    fun releaseReservation(orderId: Long) = getReservationOrThrow(orderId).release()

    @Transactional(readOnly = true)
    fun getReservationOrThrow(orderId: Long): StockReservation =
        stockReservationRepository.findByOrderId(orderId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "[orderId = $orderId] 확보한 재고 기록이 없습니다.")

    @Transactional(readOnly = true)
    fun getOwnedByOrThrow(orderId: Long, userId: Long): Order =
        orderRepository.findOwnedBy(orderId = orderId, userId = userId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getIgnoringOwnerOrThrow(orderId: Long): Order =
        orderRepository.findIgnoringOwner(orderId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    /** 소유권 확인 없음, 확인을 마친 주문의 품목만 조회할 것 */
    @Transactional(readOnly = true)
    fun getItems(orderId: Long): List<OrderItem> = orderItemRepository.findAllByOrderId(orderId)

    @Transactional(readOnly = true)
    fun getOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> =
        orderRepository.findOrdersOwnedBy(userId = userId, page = page)
}
