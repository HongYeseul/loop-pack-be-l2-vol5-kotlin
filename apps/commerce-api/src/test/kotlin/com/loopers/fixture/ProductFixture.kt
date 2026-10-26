package com.loopers.fixture

import com.loopers.product.domain.Product
import com.loopers.product.domain.ProductStatus

/**
 * 상품 생성을 한 곳으로 모음
 * brandId 기본값 없음 (상품은 살아 있는 브랜드를 참조해야 하므로 테스트가 브랜드를 먼저 생성)
 */
object ProductFixture {
    const val DEFAULT_NAME = "루퍼스 티셔츠"
    const val DEFAULT_PRICE = 3_500L
    const val DEFAULT_STOCK = 5

    fun product(
        brandId: Long,
        name: String = DEFAULT_NAME,
        price: Long = DEFAULT_PRICE,
        stock: Int = DEFAULT_STOCK,
        status: ProductStatus = ProductStatus.ON_SALE,
    ): Product = Product(brandId = brandId, name = name, price = price, stock = stock)
        .also { if (status != ProductStatus.ON_SALE) it.changeStatus(status) }

    /** 논리 삭제된 상품, 고객 조회 제외 확인용 */
    fun deletedProduct(brandId: Long, name: String = DEFAULT_NAME): Product =
        product(brandId = brandId, name = name).apply { delete() }
}
