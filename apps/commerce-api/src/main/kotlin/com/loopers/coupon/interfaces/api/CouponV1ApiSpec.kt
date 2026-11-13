package com.loopers.coupon.interfaces.api

import com.loopers.support.interfaces.api.ApiResponse
import com.loopers.user.domain.LoginId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Coupon V1 API", description = "쿠폰 발급과 본인 보유 목록 API입니다.")
interface CouponV1ApiSpec {
    @Operation(
        summary = "쿠폰 발급",
        description = """
            새 발급 요청은 202와 예약 정보를 반환합니다. 접수 완료이며 발급 성공을 뜻하지 않습니다.
            같은 요청은 기존 예약과 200, 이미 발급됐다면 같은 couponId와 200을 반환합니다.
            이미 받은 쿠폰은 행사가 종료되거나 품절되어도 그대로 반환합니다.

            발급 가능 수량이 모두 예약됐거나 품절·비활성이면 409, 현재 접수할 수 없으면 503입니다.
            결과 조회에서 발급 완료 여부를 확인하세요.
            혜택은 표시 정보이며 주문 할인에 자동 적용되지 않습니다.
        """,
        parameters = [
            Parameter(name = "X-USER-ID", `in` = ParameterIn.HEADER, required = true, schema = Schema(type = "string")),
        ],
    )
    fun issue(
        @Parameter(description = "쿠폰 행사 식별자") campaignId: Long,
        @Parameter(hidden = true) loginId: LoginId,
    ): ResponseEntity<ApiResponse<Any>>

    @Operation(
        summary = "행사별 내 쿠폰 예약 결과",
        description = "PENDING은 예약 후 발급 대기, ISSUED는 발급 완료입니다. " +
            "발급 완료 시 couponId를 반환하며 예약·발급 내역이 없으면 404입니다.",
        parameters = [
            Parameter(name = "X-USER-ID", `in` = ParameterIn.HEADER, required = true, schema = Schema(type = "string")),
        ],
    )
    fun result(@Parameter(description = "쿠폰 행사 식별자") campaignId: Long, @Parameter(hidden = true) loginId: LoginId):
        ApiResponse<CouponV1Dto.ReservationResponse>

    @Operation(
        summary = "내 보유 쿠폰 목록",
        description = "현재 사용자의 쿠폰을 발급 시각 내림차순, 같은 시각이면 couponId 내림차순으로 반환합니다. 보유 쿠폰이 없으면 빈 목록입니다.",
        parameters = [
            Parameter(name = "X-USER-ID", `in` = ParameterIn.HEADER, required = true, schema = Schema(type = "string")),
        ],
    )
    fun owned(
        @Parameter(hidden = true) loginId: LoginId,
    ): ApiResponse<List<CouponV1Dto.CouponResponse>>
}
