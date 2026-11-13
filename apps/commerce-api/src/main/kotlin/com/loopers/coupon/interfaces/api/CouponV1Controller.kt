package com.loopers.coupon.interfaces.api

import com.loopers.coupon.application.CouponFacade
import com.loopers.support.interfaces.api.ApiResponse
import com.loopers.user.domain.LoginId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CouponV1Controller(private val couponFacade: CouponFacade) : CouponV1ApiSpec {
    @PostMapping("/api/v1/coupon-campaigns/{campaignId}/issues")
    override fun issue(
        @PathVariable(value = "campaignId") campaignId: Long,
        loginId: LoginId,
    ): ResponseEntity<ApiResponse<Any>> {
        val result = couponFacade.issue(loginId, campaignId)
        val body: Any = result.coupon?.let(CouponV1Dto.CouponResponse::from)
            ?: CouponV1Dto.ReservationResponse.from(requireNotNull(result.reservation))
        return ResponseEntity.status(if (result.accepted) HttpStatus.ACCEPTED else HttpStatus.OK)
            .body(ApiResponse.success(body))
    }

    @GetMapping("/api/v1/coupon-campaigns/{campaignId}/issue")
    override fun result(@PathVariable campaignId: Long, loginId: LoginId): ApiResponse<CouponV1Dto.ReservationResponse> =
        ApiResponse.success(CouponV1Dto.ReservationResponse.from(couponFacade.result(loginId, campaignId)))

    @GetMapping("/api/v1/coupons")
    override fun owned(loginId: LoginId): ApiResponse<List<CouponV1Dto.CouponResponse>> =
        ApiResponse.success(couponFacade.owned(loginId).map(CouponV1Dto.CouponResponse::from))
}
