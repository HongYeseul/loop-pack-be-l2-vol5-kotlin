package com.loopers.point.interfaces.api

import com.loopers.point.application.PointInfo

class PointV1Dto {
    /** amount 는 non-null Long 으로 형식 검사, 0 이하·한도 초과는 Point 가 CHARGE_AMOUNT_INVALID 로 거절 */
    data class ChargeRequest(val amount: Long)

    data class BalanceResponse(val balance: Long) {
        companion object {
            fun from(info: PointInfo): BalanceResponse = BalanceResponse(balance = info.balance)
        }
    }
}
