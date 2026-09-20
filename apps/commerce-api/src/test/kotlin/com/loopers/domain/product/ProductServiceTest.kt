package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * 상품 조회의 두 갈래 (P-04 · P-33 · DS-3) — 브랜드와 같은 모양이다.
 *
 * **가짜 저장소가 계약대로 정렬한다.** 정렬은 리포지토리 인터페이스의 KDoc 에 적힌 계약이고(D-3),
 * 가짜가 진짜와 다른 순서를 주면 이 테스트가 거짓말을 한다. SQL 로 내려가는지는
 * `ProductRepositoryIntegrationTest` 가 따로 본다.
 */
class ProductServiceTest {
    private class FakeProductRepository : ProductRepository {
        private val stored = linkedMapOf<Long, Product>()
        private var sequence = 0L

        override fun save(product: Product): Product {
            val id = stored.entries.find { it.value === product }?.key ?: ++sequence
            stored[id] = product
            return product
        }

        override fun findAlive(id: Long): Product? = stored[id]?.takeIf { it.deletedAt == null }

        override fun findIncludingDeleted(id: Long): Product? = stored[id]

        /** 계약: 삭제·판매중지·단종을 빼고, 정렬 뒤에 언제나 id 내림차순 (P-09 · P-39). */
        override fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product> {
            val matched = stored.entries
                .filter { it.value.deletedAt == null && it.value.status.isOnSale }
                .filter { criteria.brandId == null || it.value.brandId == criteria.brandId }
            val sorted = when (criteria.sort) {
                ProductSort.LATEST -> matched.sortedByDescending { it.key }
                ProductSort.PRICE_ASC ->
                    matched.sortedWith(compareBy<Map.Entry<Long, Product>> { it.value.price }.thenByDescending { it.key })
            }
            return PageResult(
                items = sorted.drop(criteria.page.offset.toInt()).take(criteria.page.size).map { it.value },
                page = criteria.page.page,
                size = criteria.page.size,
                totalCount = sorted.size.toLong(),
            )
        }

        override fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Product> {
            val all = stored.entries.sortedByDescending { it.key }.map { it.value }
            return PageResult(
                items = all.drop(criteria.offset.toInt()).take(criteria.size),
                page = criteria.page,
                size = criteria.size,
                totalCount = all.size.toLong(),
            )
        }

        override fun existsAliveByBrandId(brandId: Long): Boolean =
            stored.values.any { it.deletedAt == null && it.brandId == brandId }

        override fun countAliveByBrandId(brandId: Long): Long =
            stored.values.count { it.deletedAt == null && it.brandId == brandId }.toLong()

        /** 테스트가 id 를 알고 시작할 수 있게 한다. */
        fun seed(product: Product): Long = (++sequence).also { stored[it] = product }
    }

    private val productRepository = FakeProductRepository()
    private val productService = ProductService(productRepository)

    private fun product(
        brandId: Long = 1L,
        name: String = "루퍼스 티셔츠",
        price: Long = 3_500L,
        stock: Int = 5,
    ) = Product(brandId = brandId, name = name, price = price, stock = stock)

    @DisplayName("살아 있는 상품을 찾을 때,")
    @Nested
    inner class GetAlive {
        @DisplayName("삭제되지 않았으면, 그 상품을 돌려준다.")
        @Test
        fun returnsProduct_whenAlive() {
            val id = productRepository.seed(product())

            assertThat(productService.getAliveOrThrow(id).name).isEqualTo("루퍼스 티셔츠")
        }

        @DisplayName("판매중지·단종이어도 상세로는 돌려준다 (P-39 · 목록에서만 빠진다).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun returnsProduct_whenNotOnSale(status: ProductStatus) {
            val id = productRepository.seed(product().apply { changeStatus(status) })

            assertThat(productService.getAliveOrThrow(id).status).isEqualTo(status)
        }

        @DisplayName("삭제되었으면, PRODUCT_NOT_FOUND 로 거절한다 (P-04 · P-12).")
        @Test
        fun throwsProductNotFound_whenDeleted() {
            val id = productRepository.seed(product().apply { delete() })

            val exception = assertThrows<CoreException> { productService.getAliveOrThrow(id) }

            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }

        @DisplayName("아예 없으면, 삭제된 것과 같은 오류로 답한다. 지워진 상품이 있었다는 사실을 드러내지 않는다.")
        @Test
        fun returnsSameErrorForAbsent() {
            val exception = assertThrows<CoreException> { productService.getAliveOrThrow(404L) }

            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }
    }

    @DisplayName("관리자가 상품을 찾을 때,")
    @Nested
    inner class GetIncludingDeleted {
        @DisplayName("삭제된 것도 보인다 (P-33). 그래야 왜 브랜드가 안 지워지는지 판단할 수 있다.")
        @Test
        fun returnsDeleted() {
            val id = productRepository.seed(product().apply { delete() })

            assertThat(productService.getIncludingDeletedOrThrow(id).deletedAt).isNotNull()
        }
    }

    @DisplayName("삭제된 상품은,")
    @Nested
    inner class DeletedIsNotATarget {
        @DisplayName("수정 대상이 아니다 (P-12). findAlive 로 찾으므로 손에 들어오지 않는다.")
        @Test
        fun cannotBeUpdated() {
            val id = productRepository.seed(product().apply { delete() })

            assertThrows<CoreException> { productService.changeNameAndPrice(id, "새 이름", 1_000L) }
        }

        @DisplayName("재고 변경 대상도 아니다 (P-12).")
        @Test
        fun cannotChangeStock() {
            val id = productRepository.seed(product().apply { delete() })

            assertThrows<CoreException> { productService.changeStock(id, 10) }
        }

        @DisplayName("판매 상태 변경 대상도 아니다 (P-12).")
        @Test
        fun cannotChangeStatus() {
            val id = productRepository.seed(product().apply { delete() })

            assertThrows<CoreException> { productService.changeStatus(id, ProductStatus.SUSPENDED) }
        }

        @DisplayName("다시 지워도 결과가 같다. 논리 삭제는 멱등하다 (D-2).")
        @Test
        fun deleteIsIdempotent() {
            val id = productRepository.seed(product())
            productService.delete(id)
            val first = productService.getIncludingDeletedOrThrow(id).deletedAt

            productService.delete(id)

            assertThat(productService.getIncludingDeletedOrThrow(id).deletedAt).isEqualTo(first)
        }
    }

    @DisplayName("고객 목록을 볼 때,")
    @Nested
    inner class AliveRows {
        private fun criteria(
            brandId: Long? = null,
            sort: ProductSort = ProductSort.LATEST,
            page: Int = 0,
            size: Int = 20,
        ) = ProductListCriteria(brandId = brandId, sort = sort, page = PageCriteria(page, size))

        @DisplayName("판매중지·단종된 상품은 목록에서 빠진다 (P-39).")
        @Test
        fun excludesNotOnSale() {
            productRepository.seed(product(name = "판매중"))
            productRepository.seed(product(name = "판매중지").apply { changeStatus(ProductStatus.SUSPENDED) })
            productRepository.seed(product(name = "단종").apply { changeStatus(ProductStatus.DISCONTINUED) })

            val result = productService.getAliveProducts(criteria())

            assertAll(
                { assertThat(result.items.map { it.name }).containsExactly("판매중") },
                { assertThat(result.totalCount).isEqualTo(1L) },
            )
        }

        @DisplayName("삭제된 상품도 빠진다 (P-12).")
        @Test
        fun excludesDeleted() {
            productRepository.seed(product(name = "살아있음"))
            productRepository.seed(product(name = "지워짐").apply { delete() })

            assertThat(productService.getAliveProducts(criteria()).items.map { it.name }).containsExactly("살아있음")
        }

        @DisplayName("재고 0 인 상품은 목록에 남는다. 재고없음은 판매중의 한 모습이다 (P-37).")
        @Test
        fun keepsOutOfStock() {
            productRepository.seed(product(name = "재고없음", stock = 0))

            val row = productService.getAliveProducts(criteria()).items.single()

            assertAll(
                { assertThat(row.name).isEqualTo("재고없음") },
                { assertThat(row.stock).isZero() },
            )
        }

        @DisplayName("브랜드로 거를 수 있다 (P-08).")
        @Test
        fun filtersByBrand() {
            productRepository.seed(product(brandId = 1L, name = "브랜드1 상품"))
            productRepository.seed(product(brandId = 2L, name = "브랜드2 상품"))

            assertThat(productService.getAliveProducts(criteria(brandId = 2L)).items.map { it.name })
                .containsExactly("브랜드2 상품")
        }

        @DisplayName("가격 낮은 순으로 볼 수 있다 (P-08).")
        @Test
        fun sortsByPriceAsc() {
            productRepository.seed(product(name = "비쌈", price = 9_000L))
            productRepository.seed(product(name = "쌈", price = 1_000L))

            assertThat(productService.getAliveProducts(criteria(sort = ProductSort.PRICE_ASC)).items.map { it.name })
                .containsExactly("쌈", "비쌈")
        }

        /**
         * **id 로 확인하지 않는다.** 엔티티의 `id` 는 저장돼야 생기므로 단위 테스트에서는 전부 0 이다.
         * 여기서 확인하는 것은 "가짜 저장소가 계약대로 나중에 넣은 것을 먼저 준다" 이고,
         * 그 순서가 **진짜 SQL 의 `ORDER BY ... , id DESC` 로 내려가는지**는
         * `ProductRepositoryIntegrationTest` 가 진짜 id 로 확인한다.
         */
        @DisplayName("가격이 같으면 나중에 들어온 것이 먼저다. 페이지끼리 겹치지도 빠지지도 않는다 (P-09 · D-3).")
        @Test
        fun breaksTiesByInsertionOrder() {
            (1..4).forEach { productRepository.seed(product(name = "동점 $it", price = 1_000L)) }

            val first = productService.getAliveProducts(criteria(sort = ProductSort.PRICE_ASC, page = 0, size = 2))
            val second = productService.getAliveProducts(criteria(sort = ProductSort.PRICE_ASC, page = 1, size = 2))

            assertAll(
                { assertThat(first.items.map { it.name }).containsExactly("동점 4", "동점 3") },
                { assertThat(second.items.map { it.name }).containsExactly("동점 2", "동점 1") },
                { assertThat(first.items.map { it.name }).doesNotContainAnyElementsOf(second.items.map { it.name }) },
                { assertThat(first.totalCount).isEqualTo(4L) },
            )
        }
    }

    @DisplayName("브랜드에 살아 있는 상품이 있는지 물을 때,")
    @Nested
    inner class AliveByBrand {
        @DisplayName("재고 0 인 상품도 연결로 센다 (P-11). 브랜드 쪽에서 재고 0 은 아직 살아 있는 상품이다.")
        @Test
        fun countsOutOfStockProduct() {
            productRepository.seed(product(brandId = 1L, stock = 0))

            assertAll(
                { assertThat(productService.existsAliveByBrand(1L)).isTrue() },
                { assertThat(productService.countAliveByBrand(1L)).isEqualTo(1L) },
            )
        }

        @DisplayName("단종된 상품도 연결로 센다 (P-11 · DS-11). 판매 상태는 보지 않는다.")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun countsNotOnSaleProduct(status: ProductStatus) {
            productRepository.seed(product(brandId = 1L).apply { changeStatus(status) })

            assertThat(productService.existsAliveByBrand(1L)).isTrue()
        }

        @DisplayName("삭제된 상품은 연결로 세지 않는다 (P-11). 여기서만 deletedAt 이 '연결 안 됨' 으로 읽힌다.")
        @Test
        fun ignoresDeletedProduct() {
            productRepository.seed(product(brandId = 1L).apply { delete() })

            assertAll(
                { assertThat(productService.existsAliveByBrand(1L)).isFalse() },
                { assertThat(productService.countAliveByBrand(1L)).isZero() },
            )
        }
    }
}
