package com.loopers.interfaces.api.point

import com.loopers.application.point.PointFacade
import com.loopers.domain.user.LoginId
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 충전이 `POST /charge` 인 이유: 같은 요청을 두 번 보내면 **두 번 충전되는 것이 맞다** (P-18).
 * 좋아요(D-9)와 반대다 — 거기서는 "그 상태로 만들어줘" 였고 여기서는 "이만큼 더해줘" 다.
 */
@RestController
@RequestMapping("/api/v1/points")
class PointV1Controller(
    private val pointFacade: PointFacade,
) : PointV1ApiSpec {
    @PostMapping("/charge")
    override fun charge(
        @RequestBody request: PointV1Dto.ChargeRequest,
        loginId: LoginId,
    ): ApiResponse<PointV1Dto.BalanceResponse> =
        pointFacade.charge(loginId, request.amount)
            .let { PointV1Dto.BalanceResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping
    override fun getBalance(
        loginId: LoginId,
    ): ApiResponse<PointV1Dto.BalanceResponse> =
        pointFacade.getBalance(loginId)
            .let { PointV1Dto.BalanceResponse.from(it) }
            .let { ApiResponse.success(it) }
}
