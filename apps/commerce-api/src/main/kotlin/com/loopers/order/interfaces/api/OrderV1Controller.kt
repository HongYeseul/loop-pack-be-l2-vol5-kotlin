package com.loopers.order.interfaces.api

import com.loopers.order.application.OrderFacade
import com.loopers.support.domain.PageCriteria
import com.loopers.user.domain.LoginId
import com.loopers.support.interfaces.api.ApiResponse
import com.loopers.support.interfaces.api.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 확정·취소는 상태 변경이라 POST (DELETE 는 주문 삭제로 읽힘) */
@RestController
@RequestMapping("/api/v1/orders")
class OrderV1Controller(
    private val orderFacade: OrderFacade,
) : OrderV1ApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @RequestBody request: OrderV1Dto.CreateRequest,
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.OrderResponse> =
        orderFacade.create(loginId, request.toCommand())
            .let { OrderV1Dto.OrderResponse.from(it) }
            .let { ApiResponse.success(it) }

    @PostMapping("/{orderId}/confirm")
    override fun confirm(
        @PathVariable(value = "orderId") orderId: Long,
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.ConfirmResponse> =
        orderFacade.confirm(loginId, orderId)
            .let { OrderV1Dto.ConfirmResponse.from(it) }
            .let { ApiResponse.success(it) }

    @PostMapping("/{orderId}/cancel")
    override fun cancel(
        @PathVariable(value = "orderId") orderId: Long,
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.OrderResponse> =
        orderFacade.cancel(loginId, orderId)
            .let { OrderV1Dto.OrderResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping
    override fun getOrders(
        loginId: LoginId,
        @RequestParam(value = "page", required = false) page: Int?,
        @RequestParam(value = "size", required = false) size: Int?,
    ): ApiResponse<PageResponse<OrderV1Dto.OrderSummaryResponse>> =
        orderFacade.getOrders(loginId, PageCriteria.of(page, size))
            .let { PageResponse.from(it, OrderV1Dto.OrderSummaryResponse::from) }
            .let { ApiResponse.success(it) }

    @GetMapping("/{orderId}")
    override fun getOrder(
        @PathVariable(value = "orderId") orderId: Long,
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.OrderResponse> =
        orderFacade.get(loginId, orderId)
            .let { OrderV1Dto.OrderResponse.from(it) }
            .let { ApiResponse.success(it) }
}
