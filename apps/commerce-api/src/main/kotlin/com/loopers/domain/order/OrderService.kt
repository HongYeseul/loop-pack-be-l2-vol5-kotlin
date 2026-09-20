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
    /**
     * C-9 · 생성 (P-23).
     *
     * **품목 상품이 살아 있고 팔리는지는 여기서 보지 않는다** (P-24 · P-38). 두 애그리게잇에 걸친
     * 질문이라 `OrderFacade` 가 확인하고, 단가도 거기서 복사해 온다 (P-28) —
     * `ProductService.create` 가 브랜드를 보지 않는 것과 같다 (P-05).
     */
    @Transactional
    fun create(userId: Long, items: List<OrderItem>, now: ZonedDateTime): Order =
        orderRepository.save(Order(userId = userId, items = items, now = now))

    /**
     * C-10 · C-12 의 대상 (P-26 · P-29).
     *
     * **만료 검사(설계 5절 ⑤)보다 먼저 여기서 걸러진다.** 확정·취소된 주문도 만료 시각은 지나 있어,
     * 순서가 바뀌면 이미 확정한 주문에 `ORDER_EXPIRED` 가 나간다.
     */
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

    /** C-11 · 단건. 남의 주문은 **없는 주문과 같은 오류**다 (P-02). */
    @Transactional(readOnly = true)
    fun getOwnedByOrThrow(orderId: Long, userId: Long): Order =
        orderRepository.findOwnedBy(orderId = orderId, userId = userId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    /** A-13 · 관리자의 단건. 소유권을 보지 않는 것은 저장소 메서드 이름이 들고 있다. */
    @Transactional(readOnly = true)
    fun getIgnoringOwnerOrThrow(orderId: Long): Order =
        orderRepository.findIgnoringOwner(orderId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND, "[orderId = $orderId] 주문을 찾을 수 없습니다.")

    /** C-11 · 목록. 정렬은 저장소 계약이 든다 (P-46). */
    @Transactional(readOnly = true)
    fun getOrdersOwnedBy(userId: Long, page: PageCriteria): PageResult<Order> =
        orderRepository.findOrdersOwnedBy(userId = userId, page = page)
}
