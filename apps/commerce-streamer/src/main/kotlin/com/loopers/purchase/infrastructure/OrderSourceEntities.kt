package com.loopers.purchase.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

// commerce-api의 orders 컬럼 계약과 함께 수정
@Entity
@Immutable
@Table(name = "orders")
class OrderSourceEntity(
    @Id
    @Column(updatable = false, insertable = false)
    val id: Long,
    @Column(nullable = false, length = 20, updatable = false, insertable = false)
    val status: String,
    @Column(name = "confirmed_at", columnDefinition = "datetime(6)", updatable = false, insertable = false)
    val confirmedAt: Instant?,
)

// commerce-api의 order_item 컬럼 계약과 함께 수정
@Entity
@Immutable
@Table(name = "order_item")
class OrderItemSourceEntity(
    @Id
    @Column(updatable = false, insertable = false)
    val id: Long,
    @Column(name = "order_id", nullable = false, updatable = false, insertable = false)
    val orderId: Long,
    @Column(name = "product_id", nullable = false, updatable = false, insertable = false)
    val productId: Long,
    @Column(nullable = false, updatable = false, insertable = false)
    val quantity: Int,
)
