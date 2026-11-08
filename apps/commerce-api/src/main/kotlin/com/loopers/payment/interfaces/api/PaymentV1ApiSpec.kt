package com.loopers.payment.interfaces.api

import com.loopers.support.interfaces.api.ApiResponse
import com.loopers.user.domain.LoginId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Payment V1 API", description = "로컬 테스트 PG 결제 API 입니다.")
interface PaymentV1ApiSpec {
    @Operation(
        summary = "PG 결제 시작",
        description = "본인 DRAFT 주문의 재고를 확보하고 PG 결제를 시작합니다. 금액은 주문 합계입니다. 결과 대기·미확인이면 202, 종결이면 200 입니다.",
    )
    fun start(
        @Schema(name = "주문 식별자") orderId: Long,
        @Schema(name = "결제 요청", description = "시도 키와 합성 카드 값") request: PaymentV1Dto.StartRequest,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.") loginId: LoginId,
    ): ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>>

    @Operation(
        summary = "결제 결과 확인",
        description = "본인 미완료 결제의 기존 PG 거래를 조회해 반영합니다. 새 결제를 만들지 않습니다.",
    )
    fun reconcile(
        @Schema(name = "주문 식별자") orderId: Long,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.") loginId: LoginId,
    ): ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>>

    @Operation(
        summary = "PG 결과 알림",
        description = "알림 본문의 상태는 믿지 않고, 거래를 PG 에서 다시 조회해 반영합니다.",
    )
    fun callback(
        @Schema(name = "알림") request: PaymentV1Dto.CallbackRequest,
    ): ApiResponse<Any>
}
