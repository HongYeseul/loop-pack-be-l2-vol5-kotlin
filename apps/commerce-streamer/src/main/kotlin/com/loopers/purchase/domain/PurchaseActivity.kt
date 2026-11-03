package com.loopers.purchase.domain

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Immutable
import java.time.Instant

@Entity
@Immutable
@Table(
    name = "purchase_activity",
    uniqueConstraints = [UniqueConstraint(name = "uk_purchase_order_product", columnNames = ["order_id", "product_id"])],
)
class PurchaseActivity(
    @Column(name = "order_id", nullable = false, updatable = false)
    val orderId: Long,
    @Column(name = "product_id", nullable = false, updatable = false)
    val productId: Long,
    @Column(nullable = false, updatable = false)
    val quantity: Int,
    @Column(name = "confirmed_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    val confirmedAt: Instant,
) : BaseEntity()
