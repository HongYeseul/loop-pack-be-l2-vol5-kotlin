package com.loopers.fixture

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductStatus

/**
 * 상품을 만드는 자리를 한 곳으로 모은다 — [BrandFixture] 와 같은 이유다.
 *
 * `brandId` 에 기본값을 두지 않는다. 상품은 **살아 있는 브랜드**를 참조해야 하고(P-05),
 * 기본값이 있으면 테스트가 브랜드를 만들지 않고도 상품을 만들 수 있어서 그 전제가 흐려진다.
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

    /** 논리 삭제된 상품 (D-2). 고객 조회에서 제외되는지 확인할 때 쓴다. */
    fun deletedProduct(brandId: Long, name: String = DEFAULT_NAME): Product =
        product(brandId = brandId, name = name).apply { delete() }
}
