package com.loopers.application.order

import com.loopers.domain.order.OrderItem
import com.loopers.domain.order.OrderService
import com.loopers.domain.point.PointService
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductService
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.domain.user.LoginId
import com.loopers.domain.user.UserService
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 주문·상품·포인트 **셋에 걸친 자리** (설계 5절 · DS-7).
 *
 * `Order` 는 상품을 모르고 `Product` 는 주문을 모르며 `Point` 는 둘 다 모릅니다 (설계 2-2절).
 * 확정은 그 셋을 한 트랜잭션에서 움직이는 일이라, 경계도 순서도 여기 있습니다.
 */
@Component
class OrderFacade(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val pointService: PointService,
    private val userService: UserService,
    private val clock: Clock,
) {
    /**
     * C-9 · 생성 (P-23). **재고도 포인트도 줄지 않습니다** — DRAFT 는 재고를 잡지 않습니다 (D-13).
     *
     * 단가는 상품에서 복사합니다 (P-28). 재고가 모자라는지는 보지 않습니다 —
     * 잡아두지 않으므로 지금 본 값이 확정 시점에 그대로라는 보장이 없습니다.
     */
    @Transactional
    fun create(loginId: LoginId, command: OrderCommand): OrderInfo {
        val user = userService.getActiveOrThrow(loginId)
        val products = productService.getAliveAllOrThrow(command.productIds).associateBy { it.productId }
        products.values.forEach { guardPurchasable(it) }
        val order = orderService.create(
            userId = user.userId,
            items = command.items.map {
                OrderItem(
                    productId = it.productId,
                    quantity = it.quantity,
                    unitPrice = products.getValue(it.productId).price,
                )
            },
            now = now(),
        )
        return OrderInfo.from(order)
    }

    /**
     * C-10 · 확정 (설계 5절 ③~⑨). **트랜잭션 경계가 여기입니다** (DS-7) —
     * 재고·잔액·상태가 함께 바뀌고, 어느 단계에서 거절돼도 함께 되돌아갑니다 (P-27).
     *
     * **재고를 잔액보다 먼저 봅니다** (DS-7). 잔액 부족은 충전해서 풀 수 있지만 재고 부족은
     * 고객이 할 수 있는 것이 없어서, 둘 다 부족하면 해결할 수 없는 쪽을 먼저 알립니다.
     */
    @Transactional
    fun confirm(loginId: LoginId, orderId: Long): OrderConfirmInfo {
        val now = now()
        val user = userService.getActiveOrThrow(loginId)
        val order = orderService.getDraftOwnedByOrThrow(orderId = orderId, userId = user.userId)
        if (order.isExpired(now)) {
            // 상태 변경은 배치에 맡기고 여기서는 거절만 합니다 (DS-4 · 설계 5절 ⑤)
            throw CoreException(ErrorType.ORDER_EXPIRED, "[orderId = $orderId] 만료된 주문입니다.")
        }

        val products = productService.getAliveAllOrThrow(order.productIds())
        products.forEach { guardPurchasable(it) }
        products.forEach { it.decreaseStock(order.quantityOf(it.productId)) }
        val balance = pointService.use(userId = user.userId, amount = order.totalAmount, orderId = order.orderId).balance
        order.confirm(now)

        return OrderConfirmInfo(status = order.status, paidAmount = order.totalAmount, balance = balance)
    }

    /** C-12 · 취소 (P-29). 차감한 것이 없어 되돌릴 것도 없습니다 (D-7). */
    @Transactional
    fun cancel(loginId: LoginId, orderId: Long): OrderInfo {
        val user = userService.getActiveOrThrow(loginId)
        val order = orderService.getDraftOwnedByOrThrow(orderId = orderId, userId = user.userId)
        order.cancel(now())
        return OrderInfo.from(order)
    }

    /** C-11 · 단건. 남의 주문은 **없는 주문과 같은 오류**입니다 (P-02). */
    @Transactional(readOnly = true)
    fun get(loginId: LoginId, orderId: Long): OrderInfo {
        val user = userService.getActiveOrThrow(loginId)
        return OrderInfo.from(orderService.getOwnedByOrThrow(orderId = orderId, userId = user.userId))
    }

    /** C-11 · 목록. 최신 주문순이고(P-46) 품목은 담지 않습니다. */
    @Transactional(readOnly = true)
    fun getOrders(loginId: LoginId, page: PageCriteria): PageResult<OrderSummaryInfo> {
        val user = userService.getActiveOrThrow(loginId)
        val result = orderService.getOrdersOwnedBy(userId = user.userId, page = page)
        return PageResult(
            items = result.items.map { OrderSummaryInfo.from(it) },
            page = result.page,
            size = result.size,
            totalCount = result.totalCount,
        )
    }

    /**
     * 판매중지·단종은 살 수 없습니다 (P-38). **재고는 보지 않습니다** — 재고 부족은 확정에서
     * `OUT_OF_STOCK` 으로 갈리고, 요청자가 할 일이 다릅니다 (DS-8).
     */
    private fun guardPurchasable(product: Product) {
        if (!product.status.isOnSale) {
            throw CoreException(
                ErrorType.PRODUCT_NOT_PURCHASABLE,
                "[productId = ${product.productId}, status = ${product.status}] 지금 살 수 없는 상품입니다.",
            )
        }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}
