package com.loopers.interfaces.api.order

import com.loopers.domain.user.LoginId
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.support.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Order V1 API", description = "고객용 주문 API 입니다.")
interface OrderV1ApiSpec {
    @Operation(
        summary = "주문 생성",
        description = "DRAFT 주문을 만듭니다. 재고도 포인트도 줄지 않습니다. 단가는 상품에서 복사하고, 같은 상품이 두 품목이면 거절합니다.",
    )
    fun create(
        @Schema(name = "주문 요청", description = "품목의 상품과 수량")
        request: OrderV1Dto.CreateRequest,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.OrderResponse>

    @Operation(
        summary = "주문 확정",
        description = "본인의 DRAFT 주문만 확정합니다. 재고를 먼저 보고 잔액을 봅니다. 생성 후 10분이 지나면 ORDER_EXPIRED 로 거절합니다.",
    )
    fun confirm(
        @Schema(name = "주문 식별자", description = "확정할 주문")
        orderId: Long,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.ConfirmResponse>

    @Operation(
        summary = "주문 취소",
        description = "본인의 DRAFT 주문을 거둡니다. 차감한 것이 없어 되돌릴 것도 없습니다. 확정된 주문은 대상이 아닙니다.",
    )
    fun cancel(
        @Schema(name = "주문 식별자", description = "취소할 주문")
        orderId: Long,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.OrderResponse>

    @Operation(summary = "내 주문 목록", description = "최신 주문순입니다. 한 줄에는 품목이 없습니다.")
    fun getOrders(
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
        @Schema(name = "페이지", description = "0부터 시작합니다.")
        page: Int?,
        @Schema(name = "페이지 크기", description = "1 이상 100 이하입니다.")
        size: Int?,
    ): ApiResponse<PageResponse<OrderV1Dto.OrderSummaryResponse>>

    @Operation(summary = "주문 상세", description = "품목과 만료 시각까지 보입니다. 남의 주문은 ORDER_NOT_FOUND 로 답합니다.")
    fun getOrder(
        @Schema(name = "주문 식별자", description = "조회할 주문")
        orderId: Long,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<OrderV1Dto.OrderResponse>
}
