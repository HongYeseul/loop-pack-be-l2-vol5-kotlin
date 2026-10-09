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

/** 판매 상품, deletedAt 과 status 는 별개 축 (없는 것 ≠ 못 사는 것) */
@Entity
@Table(
    name = "product",
    indexes = [
        // 브랜드 필터 + 삭제되지 않은 것
        Index(name = "idx_product_alive_brand", columnList = "deleted_at, brand_id"),
        // 가격순·최신순 + 보조 정렬
        Index(name = "idx_product_alive_price_id", columnList = "deleted_at, price, id"),
        Index(name = "idx_product_alive_created_id", columnList = "deleted_at, created_at, id"),
        // 고객 목록에서 판매중지·단종 제외
        Index(name = "idx_product_alive_status", columnList = "deleted_at, status"),
    ],
)
class Product(
    brandId: Long,
    name: String,
    price: Long,
    stock: Int,
) : SoftDeletableEntity() {
    /** 애그리게잇 경계라 식별자만 보관, 브랜드 변경 메서드 없음 */
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

    /** varchar 고정 (User.status 와 동일) */
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

    /** BaseEntity.id 의 별칭, 호출부에서 무엇의 id 인지 구분 */
    val productId: Long get() = id

    /** 상태와 재고를 모두 반영한 구매 가능 여부 */
    val purchasable: Boolean get() = status.isOnSale && stock > 0

    /** 이름·가격을 모두 검증한 뒤 변경 (일부만 바뀌는 것 방지) */
    fun changeNameAndPrice(name: String, price: Long) {
        guardName(name)
        guardPrice(price)
        this.name = name
        this.price = price
    }

    /** 재고를 최종 수량으로 설정, 반복 호출해도 결과 동일 */
    fun changeStock(quantity: Int) {
        guardStock(quantity)
        this.stock = quantity
    }

    /**
     * 재고 차감, 거절 시 변경 없음
     * 잘못된 수량과 재고 부족은 요청자 대응이 달라 오류 분리
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

    /** 재고와 무관, 재고없음은 재고에서 파생 */
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

    /** 0원 허용, 서비스 증정 상품 등록용 */
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
