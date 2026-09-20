package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 무엇을 몇 개, 얼마에 사기로 했나 (P-28).
 *
 * **[Order] 를 통해서만 만들어지고 읽힌다** (설계 2-3절). 혼자로는 뜻이 없고, 밖에서 고칠 수 있으면
 * 확정 후 금액 불변(P-28)을 지킬 자리가 흩어진다 — 그래서 모든 값이 `val` 이다.
 *
 * `Product` 객체가 아니라 [productId] 와 **복사한 [unitPrice]** 를 든다. 상품의 현재 가격을 참조하면
 * 관리자가 가격을 바꾼 순간 지난 주문 금액이 따라 바뀐다.
 */
@Entity
@Table(
    name = "order_item",
    indexes = [
        // 주문 상세 (설계 7-2절). 컬럼은 Order 가 @JoinColumn 으로 만든다
        Index(name = "idx_order_item_order", columnList = "order_id"),
    ],
)
class OrderItem(
    productId: Long,
    quantity: Int,
    unitPrice: Long,
) : BaseEntity() {
    @Column(name = "product_id", nullable = false)
    val productId: Long = productId

    @Column(name = "quantity", nullable = false)
    val quantity: Int = quantity

    /** 주문 시점의 가격을 **복사한 값**이다 (P-28). 0원을 허용한다 (D-4). */
    @Column(name = "unit_price", nullable = false)
    val unitPrice: Long = unitPrice

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
