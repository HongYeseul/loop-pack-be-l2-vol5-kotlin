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
import java.time.ZonedDateTime

/** 가짜 저장소가 계약대로 정렬 (다른 순서면 테스트가 무의미), SQL 반영은 통합 테스트 담당 */
class ProductServiceTest {
    private class FakeProductRepository : ProductRepository {
        private val stored = linkedMapOf<Long, Product>()

        /** 들어온 순서가 관계 id 순서, 마지막이 가장 최근 좋아요 */
        private val likes = mutableListOf<Pair<Long, Long>>()
        private var sequence = 0L

        override fun save(product: Product): Product {
            val id = stored.entries.find { it.value === product }?.key ?: ++sequence
            stored[id] = product
            return product
        }

        override fun findAlive(id: Long): Product? = stored[id]?.takeIf { it.deletedAt == null }

        override fun findIncludingDeleted(id: Long): Product? = stored[id]

        override fun findAliveForUpdate(id: Long): Product? = findAlive(id)

        override fun findIncludingDeletedForUpdate(id: Long): Product? = findIncludingDeleted(id)

        override fun findAliveAllForUpdate(ids: Collection<Long>): List<Product> = findAliveAll(ids)

        /** 계약: 삭제된 것과 없는 id 는 조용히 제외, 판단은 호출부 */
        override fun findAliveAll(ids: Collection<Long>): List<Product> =
            ids.distinct().mapNotNull { id -> stored[id]?.takeIf { it.deletedAt == null } }

        /** 계약: 삭제·판매중지·단종 제외, 정렬 뒤 id 내림차순 */
        override fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product> {
            val matched = stored.entries
                .filter { it.value.deletedAt == null && it.value.status.isOnSale }
                .filter { criteria.brandId == null || it.value.brandId == criteria.brandId }
            val sorted = when (criteria.sort) {
                ProductSort.LATEST -> matched.sortedByDescending { it.key }
                ProductSort.PRICE_ASC ->
                    matched.sortedWith(compareBy<Map.Entry<Long, Product>> { it.value.price }.thenByDescending { it.key })
                ProductSort.LIKES_DESC ->
                    matched.sortedWith(
                        compareByDescending<Map.Entry<Long, Product>> { entry -> likes.count { it.second == entry.key } }
                            .thenByDescending { it.key },
                    )
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

        /** 계약: 삭제된 상품만 제외, 최근 좋아요 순, 판매 상태 무관 */
        override fun findAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product> {
            val matched = likes.asReversed()
                .filter { it.first == userId }
                .mapNotNull { stored[it.second] }
                .filter { it.deletedAt == null }
            return PageResult(
                items = matched.drop(page.offset.toInt()).take(page.size),
                page = page.page,
                size = page.size,
                totalCount = matched.size.toLong(),
            )
        }

        override fun countAliveByBrandId(brandId: Long): Long =
            stored.values.count { it.deletedAt == null && it.brandId == brandId }.toLong()

        override fun deleteAliveByBrandId(brandId: Long, now: ZonedDateTime) {
            stored.values.filter { it.brandId == brandId }.forEach { it.delete() }
        }

        /** 테스트가 id 를 알고 시작하도록 지정 */
        fun seed(product: Product): Long = (++sequence).also { stored[it] = product }

        fun seedLike(userId: Long, productId: Long) {
            likes += userId to productId
        }
    }

    private val productRepository = FakeProductRepository()
    private val productService = ProductService(productRepository)

    private fun criteria(
        brandId: Long? = null,
        sort: ProductSort = ProductSort.LATEST,
        page: Int = 0,
        size: Int = 20,
    ) = ProductListCriteria(brandId = brandId, sort = sort, page = PageCriteria(page, size))

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

        @DisplayName("판매중지·단종이어도 상세로는 돌려준다 (목록에서만 빠진다).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun returnsProduct_whenNotOnSale(status: ProductStatus) {
            val id = productRepository.seed(product().apply { changeStatus(status) })

            assertThat(productService.getAliveOrThrow(id).status).isEqualTo(status)
        }

        @DisplayName("삭제되었으면, PRODUCT_NOT_FOUND 로 거절한다.")
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
        @DisplayName("삭제된 것도 보인다. 그래야 왜 브랜드가 안 지워지는지 판단할 수 있다.")
        @Test
        fun returnsDeleted() {
            val id = productRepository.seed(product().apply { delete() })

            assertThat(productService.getIncludingDeletedOrThrow(id).deletedAt).isNotNull()
        }
    }

    @DisplayName("삭제된 상품은,")
    @Nested
    inner class DeletedIsNotATarget {
        @DisplayName("수정 대상이 아니다. findAlive 로 찾으므로 손에 들어오지 않는다.")
        @Test
        fun cannotBeUpdated() {
            val id = productRepository.seed(product().apply { delete() })

            assertThrows<CoreException> { productService.changeNameAndPrice(id, "새 이름", 1_000L) }
        }

        @DisplayName("재고 변경 대상도 아니다.")
        @Test
        fun cannotChangeStock() {
            val id = productRepository.seed(product().apply { delete() })

            assertThrows<CoreException> { productService.changeStock(id, 10) }
        }

        @DisplayName("판매 상태 변경 대상도 아니다.")
        @Test
        fun cannotChangeStatus() {
            val id = productRepository.seed(product().apply { delete() })

            assertThrows<CoreException> { productService.changeStatus(id, ProductStatus.SUSPENDED) }
        }

        @DisplayName("다시 지워도 결과가 같다. 논리 삭제는 멱등하다.")
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
        @DisplayName("판매중지·단종된 상품은 목록에서 빠진다.")
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

        @DisplayName("삭제된 상품도 빠진다.")
        @Test
        fun excludesDeleted() {
            productRepository.seed(product(name = "살아있음"))
            productRepository.seed(product(name = "지워짐").apply { delete() })

            assertThat(productService.getAliveProducts(criteria()).items.map { it.name }).containsExactly("살아있음")
        }

        @DisplayName("재고 0 인 상품은 목록에 남는다. 재고없음은 판매중의 한 모습이다.")
        @Test
        fun keepsOutOfStock() {
            productRepository.seed(product(name = "재고없음", stock = 0))

            val row = productService.getAliveProducts(criteria()).items.single()

            assertAll(
                { assertThat(row.name).isEqualTo("재고없음") },
                { assertThat(row.stock).isZero() },
            )
        }

        @DisplayName("브랜드로 거를 수 있다.")
        @Test
        fun filtersByBrand() {
            productRepository.seed(product(brandId = 1L, name = "브랜드1 상품"))
            productRepository.seed(product(brandId = 2L, name = "브랜드2 상품"))

            assertThat(productService.getAliveProducts(criteria(brandId = 2L)).items.map { it.name })
                .containsExactly("브랜드2 상품")
        }

        @DisplayName("가격 낮은 순으로 볼 수 있다.")
        @Test
        fun sortsByPriceAsc() {
            productRepository.seed(product(name = "비쌈", price = 9_000L))
            productRepository.seed(product(name = "쌈", price = 1_000L))

            assertThat(productService.getAliveProducts(criteria(sort = ProductSort.PRICE_ASC)).items.map { it.name })
                .containsExactly("쌈", "비쌈")
        }

        /** id 로 확인하지 않음 (단위 테스트의 엔티티 id 는 전부 0), SQL 반영은 통합 테스트 담당 */
        @DisplayName("가격이 같으면 나중에 들어온 것이 먼저다. 페이지끼리 겹치지도 빠지지도 않는다.")
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

    @DisplayName("브랜드에 연결된 살아 있는 상품을 셀 때,")
    @Nested
    inner class AliveByBrand {
        @DisplayName("재고 0 인 상품도 연결로 센다. 브랜드 쪽에서 재고 0 은 아직 살아 있는 상품이다.")
        @Test
        fun countsOutOfStockProduct() {
            productRepository.seed(product(brandId = 1L, stock = 0))

            assertThat(productService.countAliveByBrand(1L)).isEqualTo(1L)
        }

        @DisplayName("단종된 상품도 연결로 센다. 판매 상태는 보지 않는다.")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun countsNotOnSaleProduct(status: ProductStatus) {
            productRepository.seed(product(brandId = 1L).apply { changeStatus(status) })

            assertThat(productService.countAliveByBrand(1L)).isEqualTo(1L)
        }

        @DisplayName("삭제된 상품은 연결로 세지 않는다. 여기서만 deletedAt 이 '연결 안 됨' 으로 읽힌다.")
        @Test
        fun ignoresDeletedProduct() {
            productRepository.seed(product(brandId = 1L).apply { delete() })

            assertThat(productService.countAliveByBrand(1L)).isZero()
        }
    }

    @DisplayName("좋아요 많은 순으로 찾을 때,")
    @Nested
    inner class LikesDescSort {
        @DisplayName("좋아요가 많은 상품이 먼저고, 같으면 id 내림차순이다.")
        @Test
        fun ordersByLikeCountThenId() {
            // arrange
            val popular = productRepository.seed(product(name = "인기"))
            val tiedOld = productRepository.seed(product(name = "동점 먼저"))
            val tiedNew = productRepository.seed(product(name = "동점 나중"))
            productRepository.seedLike(userId = 1L, productId = popular)
            productRepository.seedLike(userId = 2L, productId = popular)
            productRepository.seedLike(userId = 1L, productId = tiedOld)
            productRepository.seedLike(userId = 1L, productId = tiedNew)

            // act
            val result = productService.getAliveProducts(criteria(sort = ProductSort.LIKES_DESC))

            // assert
            assertThat(result.items.map { it.name }).containsExactly("인기", "동점 나중", "동점 먼저")
        }
    }

    @DisplayName("내가 좋아요한 상품을 찾을 때,")
    @Nested
    inner class LikedProducts {
        @DisplayName("삭제된 상품은 빼고, 최근에 좋아요한 순으로 준다.")
        @Test
        fun excludesDeletedAndOrdersByMostRecentlyLiked() {
            // arrange
            val first = productRepository.seed(product(name = "먼저 누름"))
            val second = productRepository.seed(product(name = "나중 누름"))
            val deleted = productRepository.seed(product(name = "지워짐").apply { delete() })
            listOf(first, second, deleted).forEach { productRepository.seedLike(userId = 1L, productId = it) }

            // act
            val result = productService.getAliveProductsLikedBy(userId = 1L, page = PageCriteria(0, 20))

            // assert
            assertAll(
                { assertThat(result.items.map { it.name }).containsExactly("나중 누름", "먼저 누름") },
                { assertThat(result.totalCount).isEqualTo(2L) },
            )
        }

        @DisplayName("남의 좋아요는 보이지 않는다.")
        @Test
        fun excludesOtherUsersLikes() {
            // arrange
            productRepository.seedLike(userId = 2L, productId = productRepository.seed(product(name = "남의 것")))

            // act & assert
            assertThat(productService.getAliveProductsLikedBy(userId = 1L, page = PageCriteria(0, 20)).items).isEmpty()
        }
    }

    @DisplayName("주문 품목의 상품을 한 번에 찾을 때,")
    @Nested
    inner class GetAliveAll {
        @DisplayName("모두 살아 있으면, 그 상품들을 돌려준다.")
        @Test
        fun returnsAll_whenAllAlive() {
            // arrange
            val first = productRepository.seed(product(name = "티셔츠"))
            val second = productRepository.seed(product(name = "양말"))

            // act
            val products = productService.getAliveAllOrThrow(listOf(first, second))

            // assert
            assertThat(products.map { it.name }).containsExactlyInAnyOrder("티셔츠", "양말")
        }

        @DisplayName("하나라도 없으면, PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        fun rejects_whenAnyMissing() {
            // arrange
            val existing = productRepository.seed(product())

            // act
            val exception = assertThrows<CoreException> { productService.getAliveAllOrThrow(listOf(existing, 999L)) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }

        /** 삭제된 상품은 재고 0 과 같은 취급, 차감할 수 없으면 확정 거절 */
        @DisplayName("삭제된 상품이 끼어 있으면, 없는 것과 같이 거절한다.")
        @Test
        fun rejects_whenDeleted() {
            // arrange
            val deleted = productRepository.seed(product().apply { delete() })

            // act
            val exception = assertThrows<CoreException> { productService.getAliveAllOrThrow(listOf(deleted)) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.PRODUCT_NOT_FOUND)
        }
    }
}
