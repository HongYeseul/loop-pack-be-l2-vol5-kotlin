package com.loopers.order.interfaces.api.admin

import com.loopers.order.application.OrderFacade
import com.loopers.admin.domain.AdminLoginId
import com.loopers.support.domain.PageCriteria
import com.loopers.support.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/orders")
class OrderAdminV1Controller(
    private val orderFacade: OrderFacade,
) : OrderAdminV1ApiSpec {
    /** userId 필수, 빠지면 핸들러 진입 전 BAD_REQUEST */
    @GetMapping
    override fun getOrders(
        @RequestParam(value = "userId") userId: Long,
        @RequestParam(value = "page", required = false) page: Int?,
        @RequestParam(value = "size", required = false) size: Int?,
        requester: AdminLoginId,
    ): ApiResponse<OrderAdminV1Dto.UserOrdersResponse> =
        orderFacade.getOrdersForAdmin(requester = requester, userId = userId, page = PageCriteria.of(page, size))
            .let { OrderAdminV1Dto.UserOrdersResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping("/{orderId}")
    override fun getOrder(
        @PathVariable(value = "orderId") orderId: Long,
        requester: AdminLoginId,
    ): ApiResponse<OrderAdminV1Dto.OrderResponse> =
        orderFacade.getForAdmin(requester = requester, orderId = orderId)
            .let { OrderAdminV1Dto.OrderResponse.from(it) }
            .let { ApiResponse.success(it) }
}
