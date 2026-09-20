package com.loopers.interfaces.api.admin.order

import com.loopers.domain.admin.AdminLoginId
import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Order Admin V1 API", description = "관리자용 주문 API 입니다. ORDER_READ 가 필요합니다.")
interface OrderAdminV1ApiSpec {
    @Operation(
        summary = "구매자별 주문 목록",
        description = "userId 가 필수입니다. 전수 조회를 두지 않는 것이 D-12 1번입니다. 구매자는 마스킹되어 함께 나갑니다.",
    )
    fun getOrders(
        userId: Long,
        page: Int?,
        size: Int?,
        requester: AdminLoginId,
    ): ApiResponse<OrderAdminV1Dto.UserOrdersResponse>

    @Operation(summary = "주문 상세", description = "누구의 주문이든 봅니다. 소유권은 고객끼리의 규칙입니다 (P-02).")
    fun getOrder(orderId: Long, requester: AdminLoginId): ApiResponse<OrderAdminV1Dto.OrderResponse>
}
