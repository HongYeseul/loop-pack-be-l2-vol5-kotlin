package com.loopers.domain.product

import com.loopers.domain.SoftDeletableEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 팔 수 있는 물건. 이름·가격·재고를 가진다 (기획 4절).
 *
 * 논리 삭제 대상이라 [SoftDeletableEntity] 를 상속한다 (D-2 · DS-10) — 주문 품목이
 * 가리키는 대상이라 행이 사라지면 지난 주문 상세를 복원할 수 없다.
 *
 * **`deletedAt` 과 [status] 는 직교한다** (DS-11). 카탈로그에서 없는 것과 있지만 못 사는 것은 다르다.
 */
@Entity
@Table(
    name = "product",
    indexes = [
        // 브랜드 필터 + 살아 있는 것만 (P-08 · P-12)
        Index(name = "idx_product_alive_brand", columnList = "deleted_at, brand_id"),
        // price_asc · latest 와 그 보조 정렬 (P-09)
        Index(name = "idx_product_alive_price_id", columnList = "deleted_at, price, id"),
        Index(name = "idx_product_alive_created_id", columnList = "deleted_at, created_at, id"),
        // 고객 목록에서 판매중지·단종 제외 (P-39)
        Index(name = "idx_product_alive_status", columnList = "deleted_at, status"),
    ],
)
class Product(
    brandId: Long,
    name: String,
    price: Long,
    stock: Int,
) : SoftDeletableEntity() {
    /**
     * 애그리게잇을 넘는 참조라 `Brand` 객체가 아니라 식별자만 든다 (설계 2-2절) —
     * 객체로 들면 상품을 읽다가 브랜드를 고칠 길이 열린다. 바꾸는 메서드는 두지 않는다 (P-05).
     */
    @Column(name = "brand_id", nullable = false)
    var brandId: Long = brandId
        protected set

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name
        protected set

    @Column(name = "price", nullable = false)
    var price: Long = price
        protected set

    @Column(name = "stock", nullable = false)
    var stock: Int = stock
        protected set

    /**
     * `varchar` 로 고정한다 (`User.status` 와 같다). `@Enumerated(STRING)` 만 두면 Hibernate 가
     * 네이티브 `ENUM` 을 만들어, 상태를 더할 때마다 `ALTER TABLE` 이 필요해진다.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: ProductStatus = ProductStatus.ON_SALE
        protected set

    init {
        guardName(name)
        guardPrice(price)
        guardStock(stock)
    }

    /**
     * 지금 살 수 있는가 — 상태와 재고를 모두 본다. 고객 응답의 "구매 가능 여부" 다 (D-10).
     * 고객에게는 재고 수량이 안 보이므로 이 하나가 구매 가능성의 전부다.
     */
    val purchasable: Boolean get() = status.isOnSale && stock > 0

    /**
     * 이름과 가격을 고친다 (A-9). 브랜드는 바꾸지 않는다 (P-05).
     *
     * 둘을 묶은 이유: 먼저 다 검사한 뒤 바꾼다. 따로 두면 **반쯤 바뀐 상품**이 생긴다.
     */
    fun changeNameAndPrice(name: String, price: Long) {
        guardName(name)
        guardPrice(price)
        this.name = name
        this.price = price
    }

    /**
     * 재고를 **최종 수량으로 설정한다** (P-07 · A-11). 증감이 아니다.
     * 같은 요청을 두 번 보내도 결과가 같다 — API 가 `PUT` 인 이유다.
     */
    fun changeStock(quantity: Int) {
        guardStock(quantity)
        this.stock = quantity
    }

    /**
     * 재고를 뺀다 (P-07 · P-27 · 대표 TDD). 판단과 변경을 **한 메서드 안에서** 하고,
     * 거절하면 아무것도 바꾸지 않는다.
     *
     * 두 거절을 다른 식별자로 나눈다 (DS-8) — 잘못된 수량은 고쳐 보내면 되고,
     * 재고 부족은 수량을 줄이거나 포기해야 한다.
     */
    fun decreaseStock(quantity: Int) {
        if (quantity <= 0) {
            throw CoreException(ErrorType.INVALID_QUANTITY, "[quantity = $quantity] 차감 수량은 1 이상이어야 합니다.")
        }
        if (stock < quantity) {
            throw CoreException(ErrorType.OUT_OF_STOCK, "[stock = $stock, quantity = $quantity] 재고가 부족합니다.")
        }
        this.stock -= quantity
    }

    /**
     * 판매 상태를 설정한다 (P-36 · A-16). 어디로 갈 수 있는지는 [ProductStatus] 가 안다.
     *
     * 재고는 건드리지 않는다. 재고없음은 재고에서 따라온다 (P-37).
     */
    fun changeStatus(next: ProductStatus) {
        if (!status.canTransitionTo(next)) {
            throw CoreException(ErrorType.BAD_REQUEST, "[$status -> $next] 허용되지 않는 판매 상태 전이입니다.")
        }
        this.status = next
    }

    private fun guardName(name: String) {
        if (name.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어있을 수 없습니다.")
        }
        if (name.length > NAME_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "상품 이름은 ${NAME_MAX_LENGTH}자를 넘을 수 없습니다.")
        }
    }

    /** 0원을 허용한다 (D-4). 서비스로 끼워 주는 상품이 있고, 금지하면 그런 상품을 등록할 수 없다. */
    private fun guardPrice(price: Long) {
        if (price !in 0..MAX_PRICE) {
            throw CoreException(ErrorType.BAD_REQUEST, "상품 가격은 0 이상 $MAX_PRICE 이하여야 합니다.")
        }
    }

    private fun guardStock(quantity: Int) {
        if (quantity < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다.")
        }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val MAX_PRICE = 100_000_000L
        const val STATUS_MAX_LENGTH = 20
    }
}
