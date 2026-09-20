package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductService(
    private val productRepository: ProductRepository,
) {
    /**
     * A-7 · 생성.
     *
     * **브랜드가 살아 있는지는 여기서 보지 않는다** (P-05). 그것은 두 애그리게잇에 걸친 질문이라
     * `ProductFacade` 가 확인한다 — `Brand` 가 상품을 모르고 `Product` 가 브랜드를 모르는
     * 단방향 설계(설계 2-3절)를 그대로 지키려면 조립하는 쪽이 물어야 한다.
     */
    @Transactional
    fun create(brandId: Long, name: String, price: Long, stock: Int): Product =
        productRepository.save(Product(brandId = brandId, name = name, price = price, stock = stock))

    /**
     * 고객 상세와 관리자 수정이 함께 쓴다 (C-3 · A-9 · A-11 · A-16).
     *
     * 삭제된 것과 아예 없는 것을 **같은 오류로** 답한다. 구분해 주면 "지워진 상품이 있었다"는
     * 사실이 새어나가고, 요청자가 할 수 있는 행동은 어느 쪽이든 같다 — 목록으로 돌아간다.
     *
     * **판매중지·단종은 거절하지 않는다.** 상세로는 볼 수 있다 (P-39).
     */
    @Transactional(readOnly = true)
    fun getAliveOrThrow(id: Long): Product =
        productRepository.findAlive(id)
            ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = $id] 상품을 찾을 수 없습니다.")

    /** 관리자 상세 (A-8). 삭제 시각까지 보인다 (P-33). */
    @Transactional(readOnly = true)
    fun getIncludingDeletedOrThrow(id: Long): Product =
        productRepository.findIncludingDeleted(id)
            ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = $id] 상품을 찾을 수 없습니다.")

    /** C-2 · 고객 목록. */
    @Transactional(readOnly = true)
    fun getAliveProducts(criteria: ProductListCriteria): PageResult<Product> = productRepository.findAliveProducts(criteria)

    /** C-6 · 내가 좋아요한 상품. 조건과 정렬은 저장소 계약이 든다 (P-45 · P-16). */
    @Transactional(readOnly = true)
    fun getAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product> =
        productRepository.findAliveProductsLikedBy(userId, page)

    /** A-6 · 관리자 목록. */
    @Transactional(readOnly = true)
    fun getAllIncludingDeleted(criteria: PageCriteria): PageResult<Product> = productRepository.findAllIncludingDeleted(criteria)

    /** A-9 · 수정. 삭제된 상품은 대상이 아니다 (P-12) — `findAlive` 로 찾으므로 손에 들어오지 않는다. */
    @Transactional
    fun changeNameAndPrice(id: Long, name: String, price: Long): Product =
        getAliveOrThrow(id).also { it.changeNameAndPrice(name, price) }

    /** A-11 · 재고 설정 (P-07). 삭제된 상품은 대상이 아니다 (P-12). */
    @Transactional
    fun changeStock(id: Long, quantity: Int): Product = getAliveOrThrow(id).also { it.changeStock(quantity) }

    /** A-16 · 판매 상태 설정 (P-36). 삭제된 상품은 대상이 아니다 (P-12). */
    @Transactional
    fun changeStatus(id: Long, status: ProductStatus): Product = getAliveOrThrow(id).also { it.changeStatus(status) }

    /** A-10 · 논리 삭제 (D-2). 이미 지워진 것을 또 지워도 결과가 같다. */
    @Transactional
    fun delete(id: Long) {
        getIncludingDeletedOrThrow(id).delete()
    }

    /** P-11 · 브랜드를 지울 수 있는지 판단하는 쪽이 묻는다. */
    @Transactional(readOnly = true)
    fun existsAliveByBrand(brandId: Long): Boolean = productRepository.existsAliveByBrandId(brandId)

    /** A-3 · 관리자 브랜드 상세의 "연결 상품 수". */
    @Transactional(readOnly = true)
    fun countAliveByBrand(brandId: Long): Long = productRepository.countAliveByBrandId(brandId)
}
