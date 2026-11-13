package com.loopers.coupon.interfaces.api.local

import com.loopers.coupon.application.CouponAdmissionOperations
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.support.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Profile("local")
@ConditionalOnProperty(prefix = "coupon.operations", name = ["enabled"], havingValue = "true")
@RestController
@RequestMapping("/api-admin/v1/local/coupon-admission")
@Tag(name = "로컬 쿠폰 수용 관리")
class LocalCouponAdmissionController(private val operations: CouponAdmissionOperations) {
    data class RecoveryRequest(val confirmed: Boolean = false, val expectedTokens: Set<String> = emptySet())

    @GetMapping
    @Operation(summary = "전체 쿠폰 발급 수용 상태 조회")
    fun status(): ApiResponse<CouponGateStatus> = ApiResponse.success(operations.status())

    @PostMapping("/close")
    @Operation(summary = "신규 발급 수용 닫기", description = "진행 중인 발급과 기존 쿠폰 조회는 유지합니다.")
    fun close(): ApiResponse<CouponGateStatus> = ApiResponse.success(operations.close())

    @PostMapping("/recover")
    @Operation(
        summary = "점검 후 신규 발급 재개",
        description = "모든 consumer의 기존 DB 작업 종료와 쿠폰 결과를 확인한 뒤 confirmed를 true로 보내세요. " +
            "종료한 실행 토큰을 정리할 때만 상태 조회의 토큰 집합을 expectedTokens에 명시하세요. " +
            "토큰 집합이 다르거나 예약 상태가 유실됐다면 재개하지 않습니다.",
    )
    fun recover(@RequestBody request: RecoveryRequest): ApiResponse<CouponGateStatus> =
        ApiResponse.success(operations.recover(request.confirmed, request.expectedTokens))
}
