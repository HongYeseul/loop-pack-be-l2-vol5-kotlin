package com.loopers.product.infrastructure

import com.loopers.product.domain.Product
import com.loopers.product.domain.ProductListCriteria
import com.loopers.product.domain.ProductRepository
import com.loopers.product.domain.ProductSort
import com.loopers.product.domain.ProductStatus
import com.loopers.support.domain.PageCriteria
import com.loopers.support.domain.PageResult
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class ProductRepositoryImpl(
    private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
    override fun save(product: Product): Product = productJpaRepository.save(product)

    override fun findAlive(id: Long): Product? = productJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findIncludingDeleted(id: Long): Product? = productJpaRepository.findByIdOrNull(id)

    /** 빈 목록은 조회 생략 (IN () 은 유효한 SQL 이 아님) */
    override fun findAliveAll(ids: Collection<Long>): List<Product> =
        if (ids.isEmpty()) emptyList() else productJpaRepository.findAllByIdInAndDeletedAtIsNull(ids.distinct())

    override fun findAliveForUpdate(id: Long): Product? = productJpaRepository.findForUpdateByIdAndDeletedAtIsNull(id)

    override fun findIncludingDeletedForUpdate(id: Long): Product? = productJpaRepository.findForUpdateById(id)

    override fun findAliveAllForUpdate(ids: Collection<Long>): List<Product> =
        if (ids.isEmpty()) emptyList() else productJpaRepository.findForUpdateByIdInAndDeletedAtIsNullOrderByIdAsc(ids.distinct())

    override fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product> {
        val page: Page<Product> = when (criteria.sort) {
            ProductSort.LATEST, ProductSort.PRICE_ASC -> findOnSale(criteria, orderOf(criteria.sort))
            // 정렬 기준이 상품에 없어 별도 조회
            ProductSort.LIKES_DESC -> productJpaRepository.findAliveOnSaleWithMostLikes(
                ProductStatus.ON_SALE,
                criteria.brandId,
                PageRequest.of(criteria.page.page, criteria.page.size),
            )
        }
        return page.toPageResult(criteria.page)
    }

    private fun findOnSale(criteria: ProductListCriteria, sort: Sort): Page<Product> {
        val pageable = PageRequest.of(criteria.page.page, criteria.page.size, sort)
        return criteria.brandId
            ?.let { productJpaRepository.findAllByDeletedAtIsNullAndStatusAndBrandId(ProductStatus.ON_SALE, it, pageable) }
            ?: productJpaRepository.findAllByDeletedAtIsNullAndStatus(ProductStatus.ON_SALE, pageable)
    }

    override fun findAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product> =
        productJpaRepository.findLikedProducts(userId, PageRequest.of(page.page, page.size)).toPageResult(page)

    override fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Product> =
        productJpaRepository.findAllByOrderByIdDesc(PageRequest.of(criteria.page, criteria.size)).toPageResult(criteria)

    override fun countAliveByBrandId(brandId: Long): Long = productJpaRepository.countByBrandIdAndDeletedAtIsNull(brandId)

    override fun deleteAliveByBrandId(brandId: Long, now: ZonedDateTime) {
        productJpaRepository.softDeleteAliveByBrandId(brandId, now)
    }

    /** 정렬 뒤 id 내림차순을 한 곳에서 부착 (createdAt 은 동점 가능, id 는 유일) */
    private fun orderOf(sort: ProductSort): Sort =
        when (sort) {
            ProductSort.LATEST -> Sort.by(Sort.Order.desc(Product::createdAt.name), Sort.Order.desc(ID))
            ProductSort.PRICE_ASC -> Sort.by(Sort.Order.asc(Product::price.name), Sort.Order.desc(ID))
            // 집계값 정렬은 서브쿼리 조회가 담당
            ProductSort.LIKES_DESC -> error("likes_desc 는 findAliveOnSaleWithMostLikes 가 정렬한다.")
        }

    private fun Page<Product>.toPageResult(criteria: PageCriteria): PageResult<Product> =
        PageResult(items = content, page = criteria.page, size = criteria.size, totalCount = totalElements)

    companion object {
        private const val ID = "id"
    }
}
