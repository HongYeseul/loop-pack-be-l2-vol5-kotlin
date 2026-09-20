package com.loopers.interfaces.api.point

import com.loopers.domain.user.LoginId
import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Point V1 API", description = "고객용 포인트 API 입니다.")
interface PointV1ApiSpec {
    @Operation(
        summary = "포인트 충전",
        description = "1회 충전액은 1 이상 1,000,000 이하입니다. 충전 결과가 잔액 상한을 넘으면 거절하고 잔액을 유지합니다.",
    )
    fun charge(
        @Schema(name = "충전 요청", description = "충전할 포인트")
        request: PointV1Dto.ChargeRequest,
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<PointV1Dto.BalanceResponse>

    @Operation(
        summary = "잔액 조회",
        description = "한 번도 충전하지 않았으면 0 입니다. 없는 사용자는 USER_NOT_FOUND 로 응답합니다.",
    )
    fun getBalance(
        @Schema(name = "요청자", description = "X-USER-ID 헤더로 받습니다.")
        loginId: LoginId,
    ): ApiResponse<PointV1Dto.BalanceResponse>
}
