package com.loopers.domain.product

import com.loopers.domain.like.ProductLike
import com.loopers.domain.support.PageCriteria
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.like.ProductLikeJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 리포지토리 **계약이 SQL 까지 내려가는지** 본다 (설계 9절).
 *
 * 단위 테스트의 가짜 저장소는 조건과 정렬을 코틀린으로 판단한다. 진짜로 확인해야 하는 것은
 * 그 조건이 `WHERE` 에, 그 정렬이 `ORDER BY` 에 들어가는지다. 특히 P-09(동점이면 `id DESC`)는
 * **DB 가 정렬해야** 보장되는 규칙이라 여기서만 확인할 수 있다.
 */
@SpringBootTest
class ProductRepositoryIntegrationTest @Autowired constructor(
    private val productRepository: ProductRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productLikeJpaRepository: ProductLikeJpaRepository,
    private val entityManager: EntityManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun brand(name: String = BrandFixture.DEFAULT_NAME): Long = brandJpaRepository.save(BrandFixture.brand(name)).brandId

    private fun criteria(
        brandId: Long? = null,
        sort: ProductSort = ProductSort.LATEST,
        page: Int = 0,
        size: Int = 20,
    ) = ProductListCriteria(brandId = brandId, sort = sort, page = PageCriteria(page, size))

    @DisplayName("논리 삭제된 상품은,")
    @Nested
    inner class SoftDeleted {
        @DisplayName("행이 남아 있지만 findAlive 에는 안 나온다 (P-12).")
        @Test
        fun isHiddenFromFindAlive() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brand()))
            product.delete()
            productJpaRepository.saveAndFlush(product)

            // act
            val alive = productRepository.findAlive(product.id)
            val includingDeleted = productRepository.findIncludingDeleted(product.id)

            // assert
            assertAll(
                { assertThat(alive).isNull() },
                { assertThat(includingDeleted).isNotNull() },
                { assertThat(includingDeleted?.deletedAt).isNotNull() },
            )
        }

        @DisplayName("고객 목록에서도 빠진다 (P-12).")
        @Test
        fun isHiddenFromCustomerList() {
            // arrange
            val brandId = brand()
            productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "살아있음"))
            val deleted = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "지워짐"))
            deleted.delete()
            productJpaRepository.saveAndFlush(deleted)

            // act
            val result = productRepository.findAliveProducts(criteria())

            // assert
            assertAll(
                { assertThat(result.items.map { it.name }).containsExactly("살아있음") },
                { assertThat(result.totalCount).isEqualTo(1L) },
            )
        }

        @DisplayName("관리자 목록에는 그대로 나온다 (P-33).")
        @Test
        fun staysInAdminList() {
            // arrange
            val deleted = productJpaRepository.save(ProductFixture.product(brandId = brand(), name = "지워짐"))
            deleted.delete()
            productJpaRepository.saveAndFlush(deleted)

            // act
            val result = productRepository.findAllIncludingDeleted(PageCriteria(page = 0, size = 20))

            // assert
            assertThat(result.items.map { it.name }).contains("지워짐")
        }
    }

    @DisplayName("판매중이 아닌 상품은,")
    @Nested
    inner class NotOnSale {
        @DisplayName("고객 목록에서 빠진다 (P-39).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun isHiddenFromCustomerList(status: ProductStatus) {
            // arrange
            val brandId = brand()
            productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "판매중"))
            productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brandId, name = "안 팖").apply { changeStatus(status) },
            )

            // act
            val result = productRepository.findAliveProducts(criteria())

            // assert
            assertThat(result.items.map { it.name }).containsExactly("판매중")
        }

        @DisplayName("상세로는 그대로 조회된다 (P-39). 목록에서만 빠진다.")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun isStillFoundByFindAlive(status: ProductStatus) {
            // arrange
            val product = productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brand()).apply { changeStatus(status) },
            )

            // act & assert
            assertThat(productRepository.findAlive(product.id)?.status).isEqualTo(status)
        }

        @DisplayName("판매 상태는 varchar 로 저장된다. 이름 그대로 읽힌다 (DS-11).")
        @Test
        fun isStoredAsVarchar() {
            // arrange
            val product = productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brand()).apply { changeStatus(ProductStatus.DISCONTINUED) },
            )

            // act
            val stored = entityManager
                .createNativeQuery("SELECT status FROM product WHERE id = :id")
                .setParameter("id", product.id)
                .singleResult

            // assert
            assertThat(stored).isEqualTo("DISCONTINUED")
        }
    }

    @DisplayName("고객 목록을 읽을 때,")
    @Nested
    inner class AliveRows {
        @DisplayName("브랜드는 id 만 들고 나온다. 이름을 붙이는 것은 application 의 일이다 (DS-1 · 설계 2-2절).")
        @Test
        fun carriesBrandIdOnly() {
            // arrange
            val brandId = brand(name = "루퍼스")
            productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "티셔츠"))

            // act
            val product = productRepository.findAliveProducts(criteria()).items.single()

            // assert
            assertAll(
                { assertThat(product.brandId).isEqualTo(brandId) },
                { assertThat(product.name).isEqualTo("티셔츠") },
            )
        }

        @DisplayName("브랜드로 거른다 (P-08).")
        @Test
        fun filtersByBrand() {
            // arrange
            val first = brand(name = "브랜드1")
            val second = brand(name = "브랜드2")
            productJpaRepository.save(ProductFixture.product(brandId = first, name = "브랜드1 상품"))
            productJpaRepository.saveAndFlush(ProductFixture.product(brandId = second, name = "브랜드2 상품"))

            // act
            val result = productRepository.findAliveProducts(criteria(brandId = second))

            // assert · 총 개수도 같은 조건을 본다
            assertAll(
                { assertThat(result.items.map { it.name }).containsExactly("브랜드2 상품") },
                { assertThat(result.totalCount).isEqualTo(1L) },
            )
        }

        @DisplayName("재고 0 인 상품도 목록에 남는다 (P-37 · 재고없음은 판매중의 한 모습이다).")
        @Test
        fun keepsOutOfStock() {
            // arrange
            productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brand(), stock = 0))

            // act & assert
            assertThat(productRepository.findAliveProducts(criteria()).items.single().stock).isZero()
        }
    }

    /**
     * **이 클래스의 핵심이다** (P-09 · D-3).
     *
     * 가격이 모두 같으면 `ORDER BY price ASC` 만으로는 순서가 정해지지 않는다. MySQL 문서가
     * "정렬 순서가 보장되지 않아 어떤 항목은 페이지마다 반복되고 어떤 항목은 한 번도 안 보일 수 있다"고
     * 직접 적어 둔 동작이다. `id` 를 보조 기준으로 붙이면 동점이 아예 없어진다.
     */
    @DisplayName("정렬 기준이 같은 상품이 여럿일 때,")
    @Nested
    inner class TieBreaking {
        @DisplayName("가격 동점 4건을 2건씩 볼 때, 두 페이지가 겹치지도 빠지지도 않는다 (설계 6-4절).")
        @Test
        fun doesNotOverlapOrSkipAcrossPages() {
            // arrange · 가격이 모두 1,000원이라 보조 기준이 없으면 순서가 정해지지 않는다
            val brandId = brand()
            repeat(4) { productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, price = 1_000L)) }
            val allIds = productJpaRepository.findAll().map { it.id }.sortedDescending()

            // act
            val first = productRepository.findAliveProducts(criteria(sort = ProductSort.PRICE_ASC, page = 0, size = 2))
            val second = productRepository.findAliveProducts(criteria(sort = ProductSort.PRICE_ASC, page = 1, size = 2))

            // assert
            assertAll(
                { assertThat(first.items.map { it.id }).containsExactly(allIds[0], allIds[1]) },
                { assertThat(second.items.map { it.id }).containsExactly(allIds[2], allIds[3]) },
                { assertThat(first.items.map { it.id }).doesNotContainAnyElementsOf(second.items.map { it.id }) },
                { assertThat(first.totalCount).isEqualTo(4L) },
            )
        }

        @DisplayName("가격 낮은 순은 가격을 먼저 보고, 그 다음 id 내림차순이다 (P-08 · P-09).")
        @Test
        fun sortsByPriceThenIdDesc() {
            // arrange
            val brandId = brand()
            productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "비쌈", price = 9_000L))
            val cheapFirst = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "쌈1", price = 1_000L))
            val cheapSecond = productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brandId, name = "쌈2", price = 1_000L),
            )

            // act
            val items = productRepository.findAliveProducts(criteria(sort = ProductSort.PRICE_ASC)).items

            // assert
            assertAll(
                { assertThat(items.map { it.name }).containsExactly("쌈2", "쌈1", "비쌈") },
                { assertThat(items.map { it.id }).containsExactly(cheapSecond.id, cheapFirst.id, items[2].id) },
            )
        }

        @DisplayName("최신순도 마지막에 id 내림차순이 붙는다. 같은 시각에 여러 건이 들어와도 순서가 정해진다 (D-3).")
        @Test
        fun latestAlsoBreaksTiesById() {
            // arrange
            val brandId = brand()
            repeat(4) { productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId)) }
            val allIds = productJpaRepository.findAll().map { it.id }.sortedDescending()

            // act
            val items = productRepository.findAliveProducts(criteria(sort = ProductSort.LATEST)).items

            // assert
            assertThat(items.map { it.id }).containsExactlyElementsOf(allIds)
        }
    }

    @DisplayName("브랜드에 연결된 상품을 셀 때,")
    @Nested
    inner class CountAliveByBrand {
        @DisplayName("재고 0 도, 판매중지·단종도 연결로 센다 (P-11 · DS-11).")
        @Test
        fun countsOutOfStockAndNotOnSale() {
            // arrange
            val brandId = brand()
            productJpaRepository.save(ProductFixture.product(brandId = brandId, stock = 0))
            productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brandId).apply { changeStatus(ProductStatus.DISCONTINUED) },
            )

            // act & assert
            assertAll(
                { assertThat(productRepository.existsAliveByBrandId(brandId)).isTrue() },
                { assertThat(productRepository.countAliveByBrandId(brandId)).isEqualTo(2L) },
            )
        }

        @DisplayName("삭제된 상품은 세지 않는다 (P-11).")
        @Test
        fun ignoresDeleted() {
            // arrange
            val brandId = brand()
            val deleted = productJpaRepository.save(ProductFixture.product(brandId = brandId))
            deleted.delete()
            productJpaRepository.saveAndFlush(deleted)

            // act & assert
            assertAll(
                { assertThat(productRepository.existsAliveByBrandId(brandId)).isFalse() },
                { assertThat(productRepository.countAliveByBrandId(brandId)).isZero() },
            )
        }
    }

    @DisplayName("내가 좋아요한 상품을 읽을 때,")
    @Nested
    inner class LikedProducts {
        private val userId = 1L
        private val otherUserId = 2L

        private fun like(productId: Long, userId: Long = this.userId) {
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = userId, productId = productId))
        }

        private fun page(size: Int = 20, index: Int = 0) =
            productRepository.findAliveProductsLikedBy(userId = userId, page = PageCriteria(index, size))

        @DisplayName("최근에 좋아요한 것이 먼저다 — 내가 누른 순서다 (P-45).")
        @Test
        fun ordersByMostRecentlyLiked() {
            // arrange
            val brandId = brand()
            val first = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "먼저 누름"))
            val second = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "나중 누름"))
            like(first.productId)
            like(second.productId)

            // act & assert
            assertThat(page().items.map { it.name }).containsExactly("나중 누름", "먼저 누름")
        }

        @DisplayName("삭제된 상품은 빠진다 (P-45 · P-16).")
        @Test
        fun excludesDeletedProduct() {
            // arrange
            val brandId = brand()
            val alive = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "살아있음"))
            val deleted = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "지워짐"))
            deleted.delete()
            productJpaRepository.saveAndFlush(deleted)
            like(alive.productId)
            like(deleted.productId)

            // act
            val result = page()

            // assert
            assertAll(
                { assertThat(result.items.map { it.name }).containsExactly("살아있음") },
                { assertThat(result.totalCount).isEqualTo(1L) },
            )
        }

        /** 삭제와 판매 상태는 다른 축이다 (DS-11). 내가 좋아요한 기록은 카탈로그가 아니라 내 기록이다. */
        @DisplayName("판매중지·단종된 상품은 남는다 — C-2 와 조건이 다르다 (P-45).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun keepsNotOnSaleProduct(status: ProductStatus) {
            // arrange
            val product = productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brand(), status = status),
            )
            like(product.productId)

            // act & assert
            assertThat(page().items.map { it.productId }).containsExactly(product.productId)
        }

        @DisplayName("남이 좋아요한 상품은 나오지 않는다 (P-02).")
        @Test
        fun excludesOtherUsersLikes() {
            // arrange
            val brandId = brand()
            val mine = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "내 것"))
            val theirs = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "남의 것"))
            like(mine.productId)
            like(theirs.productId, userId = otherUserId)

            // act & assert
            assertThat(page().items.map { it.name }).containsExactly("내 것")
        }

        @DisplayName("페이지가 겹치지 않고, 총 개수가 같은 조건에서 나온다 (D-3).")
        @Test
        fun pagesWithoutOverlap() {
            // arrange
            val brandId = brand()
            (1..4).forEach {
                val product = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "상품 $it"))
                like(product.productId)
            }

            // act
            val first = page(size = 2, index = 0)
            val second = page(size = 2, index = 1)

            // assert
            assertAll(
                { assertThat(first.items.map { it.name }).containsExactly("상품 4", "상품 3") },
                { assertThat(second.items.map { it.name }).containsExactly("상품 2", "상품 1") },
                { assertThat(first.totalCount).isEqualTo(4L) },
                { assertThat(second.totalCount).isEqualTo(4L) },
            )
        }
    }

    @DisplayName("좋아요 많은 순으로 읽을 때,")
    @Nested
    inner class LikesDescSort {
        private fun like(productId: Long, userId: Long) {
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = userId, productId = productId))
        }

        private fun names(brandId: Long? = null, size: Int = 20, index: Int = 0) =
            productRepository
                .findAliveProducts(criteria(brandId = brandId, sort = ProductSort.LIKES_DESC, page = index, size = size))
                .items
                .map { it.name }

        @DisplayName("좋아요가 많은 상품이 먼저다 (P-08).")
        @Test
        fun ordersByLikeCount() {
            // arrange
            val brandId = brand()
            val popular = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "인기"))
            val quiet = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "조용"))
            like(popular.productId, userId = 1L)
            like(popular.productId, userId = 2L)
            like(quiet.productId, userId = 1L)

            // act & assert
            assertThat(names()).containsExactly("인기", "조용")
        }

        /** 집계값으로 정렬해도 P-09 는 그대로다. 아무도 안 누른 상품끼리도 순서가 정해져야 한다. */
        @DisplayName("좋아요 수가 같으면 id 내림차순이다 (P-09 · D-3).")
        @Test
        fun breaksTiesById() {
            // arrange
            val brandId = brand()
            (1..4).forEach {
                productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "동점 $it"))
            }

            // act
            val first = names(size = 2, index = 0)
            val second = names(size = 2, index = 1)

            // assert
            assertAll(
                { assertThat(first).containsExactly("동점 4", "동점 3") },
                { assertThat(second).containsExactly("동점 2", "동점 1") },
                { assertThat(first).doesNotContainAnyElementsOf(second) },
            )
        }

        @DisplayName("좋아요가 많아도 삭제·판매중지·단종된 상품은 빠진다 (P-12 · P-39).")
        @Test
        fun stillExcludesHiddenProducts() {
            // arrange
            val brandId = brand()
            val deleted = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "지워짐"))
            deleted.delete()
            productJpaRepository.saveAndFlush(deleted)
            val suspended = productJpaRepository.saveAndFlush(
                ProductFixture.product(brandId = brandId, name = "판매중지", status = ProductStatus.SUSPENDED),
            )
            val onSale = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId, name = "판매중"))
            like(deleted.productId, userId = 1L)
            like(deleted.productId, userId = 2L)
            like(suspended.productId, userId = 1L)

            // act & assert
            assertThat(names()).containsExactly("판매중")
            assertThat(onSale.productId).isNotZero()
        }

        @DisplayName("브랜드 필터와 함께 걸린다 (P-08).")
        @Test
        fun appliesBrandFilter() {
            // arrange
            val mine = brand("내 브랜드")
            val other = brand("남의 브랜드")
            val target = productJpaRepository.save(ProductFixture.product(brandId = mine, name = "내 상품"))
            val outside = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = other, name = "남의 상품"))
            like(target.productId, userId = 1L)
            like(outside.productId, userId = 1L)
            like(outside.productId, userId = 2L)

            // act & assert
            assertThat(names(brandId = mine)).containsExactly("내 상품")
        }

        @DisplayName("총 개수가 목록과 같은 조건에서 나온다 (D-3).")
        @Test
        fun countsWithSameCondition() {
            // arrange
            val brandId = brand()
            val alive = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "살아있음"))
            val deleted = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "지워짐"))
            deleted.delete()
            productJpaRepository.saveAndFlush(deleted)
            like(alive.productId, userId = 1L)

            // act
            val result = productRepository.findAliveProducts(criteria(sort = ProductSort.LIKES_DESC, size = 1))

            // assert
            assertThat(result.totalCount).isEqualTo(1L)
        }
    }
}
