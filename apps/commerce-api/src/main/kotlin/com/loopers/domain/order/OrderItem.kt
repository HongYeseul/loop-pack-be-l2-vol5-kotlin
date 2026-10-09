package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

/** 주문 시점 단가를 복사해 보관, 상품 가격이 바뀌어도 지난 주문 금액 유지 */
@Entity
@Table(
    name = "order_item",
    indexes = [
        // 주문 상세 조회
        Index(name = "idx_order_item_order", columnList = "order_id"),
    ],
)
class OrderItem(
    orderId: Long,
    line: OrderLine,
) : BaseEntity() {
    @Column(name = "order_id", nullable = false)
    val orderId: Long = orderId

    @Column(name = "product_id", nullable = false)
    val productId: Long = line.productId

    @Column(name = "quantity", nullable = false)
    val quantity: Int = line.quantity

    @Column(name = "unit_price", nullable = false)
    val unitPrice: Long = line.unitPrice
}
