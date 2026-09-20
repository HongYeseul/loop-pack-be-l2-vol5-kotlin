package com.loopers.interfaces.api.point

import com.loopers.application.point.PointInfo

class PointV1Dto {
    /**
     * C-7 · 충전 요청.
     *
     * **`amount` 가 non-null `Long` 인 것이 형식 검사다** (DS-2). 누락·`null`·숫자가 아닌 값은
     * Jackson 이 거절해 `BAD_REQUEST` 가 되고, 0·음수·한도 초과는 `Point` 가 거절해
     * `CHARGE_AMOUNT_INVALID` 가 된다 (P-19). **요청자가 고칠 대상이 다르다.**
     */
    data class ChargeRequest(val amount: Long)

    /** C-7 · C-8 공통 응답. 둘 다 "지금 얼마인가" 에 답한다. */
    data class BalanceResponse(val balance: Long) {
        companion object {
            fun from(info: PointInfo): BalanceResponse = BalanceResponse(balance = info.balance)
        }
    }
}
