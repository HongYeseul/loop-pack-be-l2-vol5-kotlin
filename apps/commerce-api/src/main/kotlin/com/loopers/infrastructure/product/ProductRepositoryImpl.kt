package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductListCriteria
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class ProductRepositoryImpl(
    private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
    override fun save(product: Product): Product = productJpaRepository.save(product)

    /** "삭제되지 않음" 을 SQL 조건으로 내린다. 메모리에서 거르면 페이지 수가 어긋난다. */
    override fun findAlive(id: Long): Product? = productJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findIncludingDeleted(id: Long): Product? = productJpaRepository.findByIdOrNull(id)

    /** 빈 목록이면 조회하지 않는다 — `IN ()` 은 SQL 이 되지 않는다. */
    override fun findAliveAll(ids: Collection<Long>): List<Product> =
        if (ids.isEmpty()) emptyList() else productJpaRepository.findAllByIdInAndDeletedAtIsNull(ids.distinct())

    /**
     * 고객 목록 (C-2). **거르기·정렬·페이징을 전부 DB 가 한다.**
     *
     * 총 개수도 같은 조건에서 나온다 — `Page.totalElements` 가 조건을 물려받으므로
     * "총 10건인데 페이지를 넘기면 8건" 이 될 자리가 없다.
     */
    override fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product> {
        val page: Page<Product> = when (criteria.sort) {
            ProductSort.LATEST, ProductSort.PRICE_ASC -> findOnSale(criteria, orderOf(criteria.sort))
            // 정렬 기준이 상품에 없는 값이라 조회가 따로다 (DS-1)
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

    /** C-6 · 거르기·정렬·페이징을 전부 DB 가 한다. 정렬 기준(관계의 id)이 SQL 안에 있다. */
    override fun findAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product> =
        productJpaRepository.findLikedProducts(userId, PageRequest.of(page.page, page.size)).toPageResult(page)

    override fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Product> =
        productJpaRepository.findAllByOrderByIdDesc(PageRequest.of(criteria.page, criteria.size)).toPageResult(criteria)

    override fun existsAliveByBrandId(brandId: Long): Boolean = productJpaRepository.existsByBrandIdAndDeletedAtIsNull(brandId)

    override fun countAliveByBrandId(brandId: Long): Long = productJpaRepository.countByBrandIdAndDeletedAtIsNull(brandId)

    /**
     * **정렬 뒤에 언제나 `id` 내림차순을 붙인다** (P-09 · D-3). 한 군데에 모아 두어야
     * 정렬이 늘 때 빠뜨리지 않는다.
     *
     * `createdAt` 은 정렬 대상이지 **동점을 깨는 기준이 아니다** — 같은 시각에 여러 건이 들어오면
     * 또 동점이 된다. `id` 는 유일하다.
     */
    private fun orderOf(sort: ProductSort): Sort =
        when (sort) {
            ProductSort.LATEST -> Sort.by(Sort.Order.desc(Product::createdAt.name), Sort.Order.desc(ID))
            ProductSort.PRICE_ASC -> Sort.by(Sort.Order.asc(Product::price.name), Sort.Order.desc(ID))
            // 부르는 쪽이 이미 갈라 놓는다. 집계값은 Sort 로 표현되지 않아 서브쿼리 조회가 정렬을 든다 (DS-1)
            ProductSort.LIKES_DESC -> error("likes_desc 는 findAliveOnSaleWithMostLikes 가 정렬한다.")
        }

    private fun Page<Product>.toPageResult(criteria: PageCriteria): PageResult<Product> =
        PageResult(items = content, page = criteria.page, size = criteria.size, totalCount = totalElements)

    companion object {
        private const val ID = "id"
    }
}
