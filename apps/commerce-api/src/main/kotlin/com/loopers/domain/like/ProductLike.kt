package com.loopers.domain.like

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 사용자와 상품 사이의 관계 하나 (기획 4절 · P-14).
 *
 * 상품의 속성이 아니라 **독립 엔티티**다 (설계 2-2절). 그래서 좋아요 수는 저장하지 않고 센다 (P-15).
 *
 * 취소하면 행이 사라지므로(P-17 · D-2) [BaseEntity] 를 상속한다 — `deletedAt` 이 없으니
 * `delete()` 도 컴파일되지 않는다 (DS-10).
 *
 * 두 값 다 `Long` 이라 뒤집어 넣어도 컴파일된다 (DS-13). 부르는 쪽이 **명명 인자**로 부르고,
 * 어느 컬럼에 무엇이 들어갔는지는 테스트가 본다.
 */
@Entity
@Table(
    name = "product_like",
    uniqueConstraints = [
        // P-14 · 같은 사용자–상품 조합은 한 행뿐이다
        UniqueConstraint(name = "uk_product_like_user_product", columnNames = ["user_id", "product_id"]),
    ],
    indexes = [
        // 좋아요 수 세기 (P-15 · 설계 7-2절)
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
