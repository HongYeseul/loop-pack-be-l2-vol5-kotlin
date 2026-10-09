package com.loopers.application.order

import com.loopers.application.user.UserInfo
import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminPermission
import com.loopers.domain.admin.AdminUserService
import com.loopers.domain.order.OrderLine
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

@Component
class OrderFacade(
    private val orderService: OrderService,
    private val productService: ProductService,
    private val pointService: PointService,
    private val userService: UserService,
    private val adminUserService: AdminUserService,
    private val clock: Clock,
) {
    @Transactional
    fun create(loginId: LoginId, command: OrderCommand): OrderInfo {
        val user = userService.getActiveOrThrow(loginId)
        val products = productService.getAliveAllOrThrow(command.productIds).associateBy { it.productId }
        products.values.forEach { guardPurchasable(it) }
        val order = orderService.create(
            userId = user.userId,
            lines = command.items.map {
                OrderLine(
                    productId = it.productId,
                    quantity = it.quantity,
                    unitPrice = products.getValue(it.productId).price,
                )
            },
            now = now(),
        )
        return OrderInfo.from(order, orderService.getItems(order.orderId))
    }

    /** 잠금 순서 주문 → 상품(id 순) → 포인트, 모든 경로가 같은 순서여야 교착이 없음 */
    @Transactional
    fun confirm(loginId: LoginId, orderId: Long): OrderConfirmInfo {
        val now = now()
        val user = userService.getActiveOrThrow(loginId)
        // 주문을 먼저 잠가, 거절될 요청이 재고 행 대기열에 서기 전에 걸러냄
        val order = orderService.getDraftOwnedForUpdateOrThrow(orderId = orderId, userId = user.userId)
        if (order.isExpired(now)) {
            // 상태 변경은 배치 담당, 여기서는 거절만
            throw CoreException(ErrorType.ORDER_EXPIRED, "[orderId = $orderId] 만료된 주문입니다.")
        }

        val items = orderService.getItems(order.orderId)
        val products = productService.getAliveAllForUpdateOrThrow(items.map { it.productId })
        products.forEach { guardPurchasable(it) }
        val quantities = items.associate { it.productId to it.quantity }
        products.forEach { it.decreaseStock(quantities.getValue(it.productId)) }
        val balance = pointService.use(userId = user.userId, amount = order.totalAmount, orderId = order.orderId).balance
        order.confirm(now)

        return OrderConfirmInfo(status = order.status, paidAmount = order.totalAmount, balance = balance)
    }

    @Transactional
    fun cancel(loginId: LoginId, orderId: Long): OrderInfo {
        val user = userService.getActiveOrThrow(loginId)
        val order = orderService.getDraftOwnedForUpdateOrThrow(orderId = orderId, userId = user.userId)
        order.cancel(now())
        return OrderInfo.from(order, orderService.getItems(orderId))
    }

    @Transactional(readOnly = true)
    fun get(loginId: LoginId, orderId: Long): OrderInfo {
        val user = userService.getActiveOrThrow(loginId)
        val order = orderService.getOwnedByOrThrow(orderId = orderId, userId = user.userId)
        return OrderInfo.from(order, orderService.getItems(order.orderId))
    }

    /** 최신 주문순, 품목 미포함 */
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
     * 구매자 지정 필수 (전수 목록 금지)
     * 구매자는 마스킹 상태로 반환, ORDER_READ 로는 원래 값 접근 불가
     */
    @Transactional(readOnly = true)
    fun getOrdersForAdmin(requester: AdminLoginId, userId: Long, page: PageCriteria): UserOrdersInfo {
        adminUserService.requirePermission(requester, AdminPermission.ORDER_READ)
        val user = userService.getByIdOrThrow(userId)
        val result = orderService.getOrdersOwnedBy(userId = user.userId, page = page)
        return UserOrdersInfo(
            user = UserInfo.from(user),
            orders = PageResult(
                items = result.items.map { OrderSummaryInfo.from(it) },
                page = result.page,
                size = result.size,
                totalCount = result.totalCount,
            ),
        )
    }

    /** 소유권 확인 없음 */
    @Transactional(readOnly = true)
    fun getForAdmin(requester: AdminLoginId, orderId: Long): OrderInfo {
        adminUserService.requirePermission(requester, AdminPermission.ORDER_READ)
        val order = orderService.getIgnoringOwnerOrThrow(orderId)
        return OrderInfo.from(order, orderService.getItems(order.orderId))
    }

    /** 판매중지·단종만 거절, 재고 부족은 확정 단계에서 OUT_OF_STOCK 으로 처리 */
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
