package com.loopers.domain.like

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** 사용자-상품 관계, 독립 엔티티라 좋아요 수는 저장하지 않고 집계 */
@Entity
@Table(
    name = "product_like",
    uniqueConstraints = [
        // 같은 사용자-상품 조합은 한 행
        UniqueConstraint(name = "uk_product_like_user_product", columnNames = ["user_id", "product_id"]),
    ],
    indexes = [
        // 상품별 좋아요 수 집계
        Index(name = "idx_product_like_product", columnList = "product_id"),
    ],
)
class ProductLike(
    userId: Long,
    productId: Long,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false)
    val userId: Long = userId

    @Column(name = "product_id", nullable = false)
    val productId: Long = productId
}
