package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

/** 주문 시점 단가를 복사해 보관, 상품 가격이 바뀌어도 지난 주문 금액 유지 */
@Entity
@Table(
    name = "order_item",
    indexes = [
        // 주문 상세 조회, 컬럼은 Order 의 @JoinColumn 이 생성
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

    /** 주문 시점 가격 복사, 0원 허용 */
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
