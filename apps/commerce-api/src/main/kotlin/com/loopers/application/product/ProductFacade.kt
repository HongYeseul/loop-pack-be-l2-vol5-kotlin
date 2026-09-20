package com.loopers.application.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandService
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductListCriteria
import com.loopers.domain.product.ProductService
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component

/**
 * 상품과 브랜드를 **함께 보는 자리** (DS-1).
 *
 * `Product` 는 `brandId` 만 들고 `Brand` 는 상품을 모르므로(설계 2-2절 · 2-3절) 둘을 잇는 일은
 * 어느 도메인의 일도 아닙니다. 목록은 한 페이지 분량의 `brandId` 를 모아 **한 번에** 읽습니다.
 */
@Component
class ProductFacade(
    private val productService: ProductService,
    private val brandService: BrandService,
) {
    /** C-2 · 고객 목록. 삭제·판매중지·단종은 빠집니다 (P-12 · P-39). */
    fun getAll(criteria: ProductListCriteria): PageResult<ProductInfo> =
        productService.getAliveProducts(criteria).withBrands()

    /** A-6 · 관리자 목록. 삭제된 것도 보입니다 (P-33). */
    fun getAllForAdmin(criteria: PageCriteria): PageResult<ProductInfo> =
        productService.getAllIncludingDeleted(criteria).withBrands()

    /** C-3 · 고객 상세. 판매중지·단종도 보입니다 — 목록에서만 빠집니다 (P-39). */
    fun get(productId: Long): ProductInfo = productService.getAliveOrThrow(productId).withBrand()

    /** A-8 · 관리자 상세. 삭제 시각까지 보입니다 (P-33). */
    fun getForAdmin(productId: Long): ProductInfo = productService.getIncludingDeletedOrThrow(productId).withBrand()

    /**
     * A-7 · 생성. **살아 있는 브랜드인지 여기서 확인합니다** (P-05) —
     * `getAliveOrThrow` 가 거절하므로 지워진 브랜드에 상품이 붙는 일이 없습니다.
     */
    fun create(brandId: Long, name: String, price: Long, stock: Int): ProductInfo {
        val brand = brandService.getAliveOrThrow(brandId)
        return ProductInfo.of(productService.create(brandId, name, price, stock), brand)
    }

    /** A-9 · 수정. 이름과 가격만 바뀝니다. **브랜드는 못 바꿉니다** (P-05). */
    fun changeNameAndPrice(productId: Long, name: String, price: Long): ProductInfo =
        productService.changeNameAndPrice(productId, name, price).withBrand()

    /** A-11 · 재고 설정 (P-07). 증감이 아니라 최종 수량입니다. */
    fun changeStock(productId: Long, quantity: Int): ProductInfo = productService.changeStock(productId, quantity).withBrand()

    /** A-16 · 판매 상태 설정 (P-36). 단종은 되돌릴 수 없습니다. */
    fun changeStatus(productId: Long, status: ProductStatus): ProductInfo =
        productService.changeStatus(productId, status).withBrand()

    /** A-10 · 논리 삭제 (D-2). */
    fun delete(productId: Long) = productService.delete(productId)

    private fun Product.withBrand(): ProductInfo = ProductInfo.of(this, brandOf(brandId))

    private fun PageResult<Product>.withBrands(): PageResult<ProductInfo> {
        val brands = brandService.getAllByIds(items.map { it.brandId })
        return PageResult(
            items = items.map { ProductInfo.of(it, brands.requireBrand(it.brandId)) },
            page = page,
            size = size,
            totalCount = totalCount,
        )
    }

    /**
     * 단건 경로는 **삭제된 브랜드도 읽습니다** — P-11 이 "살아 있는 상품의 브랜드는 반드시 살아 있다"를
     * 보장하므로 고객 경로에서는 나올 수 없고, 관리자는 삭제된 상품을 보니 그 브랜드도 보여야 합니다 (P-33).
     */
    private fun brandOf(brandId: Long): Brand =
        brandService.getAllByIds(listOf(brandId)).requireBrand(brandId)

    /**
     * 브랜드 행이 아예 없는 것은 **요청자가 고칠 수 있는 일이 아닙니다** (설계 2-4절).
     * P-11 의 불변식이 깨진 상태라 없는 대상 오류가 아니라 내부 오류로 봅니다.
     */
    private fun Map<Long, Brand>.requireBrand(brandId: Long): Brand =
        this[brandId] ?: throw CoreException(
            ErrorType.INTERNAL_ERROR,
            "[brandId = $brandId] 상품이 가리키는 브랜드가 없습니다. (P-11 불변식 위반)",
        )
}
