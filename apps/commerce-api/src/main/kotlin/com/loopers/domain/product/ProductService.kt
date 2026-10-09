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
    /** 브랜드 생존 확인은 ProductFacade 담당 (애그리게잇 경계) */
    @Transactional
    fun create(brandId: Long, name: String, price: Long, stock: Int): Product =
        productRepository.save(Product(brandId = brandId, name = name, price = price, stock = stock))

    /**
     * 삭제된 것과 없는 것을 같은 오류로 응답 (삭제 사실 노출 방지)
     * 판매중지·단종은 거절하지 않음
     */
    @Transactional(readOnly = true)
    fun getAliveOrThrow(id: Long): Product =
        productRepository.findAlive(id)
            ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = $id] 상품을 찾을 수 없습니다.")

    /**
     * 하나라도 없거나 삭제되었으면 거절
     * 반환 개수로 판단 (미저장 엔티티는 id 가 0)
     */
    @Transactional(readOnly = true)
    fun getAliveAllOrThrow(ids: Collection<Long>): List<Product> {
        val distinct = ids.distinct()
        return productRepository.findAliveAll(distinct).also {
            if (it.size != distinct.size) {
                throw CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productIds = $distinct] 상품을 찾을 수 없습니다.")
            }
        }
    }

    @Transactional(readOnly = true)
    fun getIncludingDeletedOrThrow(id: Long): Product =
        productRepository.findIncludingDeleted(id)
            ?: throw CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = $id] 상품을 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getAliveProducts(criteria: ProductListCriteria): PageResult<Product> = productRepository.findAliveProducts(criteria)

    @Transactional(readOnly = true)
    fun getAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product> =
        productRepository.findAliveProductsLikedBy(userId, page)

    @Transactional(readOnly = true)
    fun getAllIncludingDeleted(criteria: PageCriteria): PageResult<Product> = productRepository.findAllIncludingDeleted(criteria)

    @Transactional
    fun changeNameAndPrice(id: Long, name: String, price: Long): Product =
        getAliveOrThrow(id).also { it.changeNameAndPrice(name, price) }

    @Transactional
    fun changeStock(id: Long, quantity: Int): Product = getAliveOrThrow(id).also { it.changeStock(quantity) }

    @Transactional
    fun changeStatus(id: Long, status: ProductStatus): Product = getAliveOrThrow(id).also { it.changeStatus(status) }

    /** 이미 삭제된 것을 다시 삭제해도 결과 동일 */
    @Transactional
    fun delete(id: Long) {
        getIncludingDeletedOrThrow(id).delete()
    }

    @Transactional(readOnly = true)
    fun existsAliveByBrand(brandId: Long): Boolean = productRepository.existsAliveByBrandId(brandId)

    @Transactional(readOnly = true)
    fun countAliveByBrand(brandId: Long): Long = productRepository.countAliveByBrandId(brandId)
}
