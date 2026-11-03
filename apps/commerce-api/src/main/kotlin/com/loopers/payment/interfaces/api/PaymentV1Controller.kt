package com.loopers.payment.interfaces.api

import com.loopers.payment.application.PaymentFacade
import com.loopers.payment.application.PaymentInfo
import com.loopers.support.interfaces.api.ApiResponse
import com.loopers.user.domain.LoginId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PaymentV1Controller(
    private val paymentFacade: PaymentFacade,
) : PaymentV1ApiSpec {
    @PostMapping("/api/v1/orders/{orderId}/payment")
    override fun start(
        @PathVariable(value = "orderId") orderId: Long,
        @RequestBody request: PaymentV1Dto.StartRequest,
        loginId: LoginId,
    ): ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> =
        respond(paymentFacade.start(loginId, orderId, request.toCommand()))

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    override fun reconcile(
        @PathVariable(value = "orderId") orderId: Long,
        loginId: LoginId,
    ): ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> =
        respond(paymentFacade.reconcile(loginId, orderId))

    @PostMapping("/api/v1/payments/callback")
    override fun callback(
        @RequestBody request: PaymentV1Dto.CallbackRequest,
    ): ApiResponse<Any> {
        paymentFacade.handleCallback(providerOrderId = request.orderId, transactionKey = request.transactionKey)
        return ApiResponse.success()
    }

    /** 결과 대기·미확인은 202, 같은 시도의 종결 결과는 200 */
    private fun respond(info: PaymentInfo): ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> =
        ResponseEntity.status(if (info.paymentStatus.isFinal) HttpStatus.OK else HttpStatus.ACCEPTED)
            .body(ApiResponse.success(PaymentV1Dto.PaymentResponse.from(info)))
}
