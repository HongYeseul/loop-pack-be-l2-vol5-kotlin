package com.loopers.domain.order

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/** 주문 번호가 생기기 전의 품목, 주문 생성 시 검증과 합계 계산에 사용 */
data class OrderLine(
    val productId: Long,
    val quantity: Int,
    /** 주문 시점 가격 복사, 0원 허용 */
    val unitPrice: Long,
) {
    init {
        if (quantity <= 0) {
            throw CoreException(ErrorType.INVALID_QUANTITY, "[quantity = $quantity] 수량은 1 이상이어야 합니다.")
        }
        if (unitPrice < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "[unitPrice = $unitPrice] 단가는 0 이상이어야 합니다.")
        }
    }

    val amount: Long get() = unitPrice * quantity
}
