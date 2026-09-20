package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.domain.like.ProductLike
import com.loopers.domain.product.ProductStatus
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.like.ProductLikeJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * C-2 · C-3 고객 상품 조회.
 *
 * 응답을 [JsonNode] 로 받는다. **응답에 없어야 할 키가 없는지**(D-10 · DS-5) 도 확인 대상이라,
 * 타입이 있는 DTO 로 받으면 그 키가 있든 없든 테스트가 통과해버린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val productLikeJpaRepository: ProductLikeJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val LIST = "/api/v1/products"
        private val DETAIL: (Any) -> String = { id -> "/api/v1/products/$id" }
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun get(url: String) = testRestTemplate.exchange(url, HttpMethod.GET, HttpEntity<Any>(Unit), JsonNode::class.java)

    private fun brandId(name: String = BrandFixture.DEFAULT_NAME): Long = brandJpaRepository.save(BrandFixture.brand(name)).brandId

    @DisplayName("GET /api/v1/products")
    @Nested
    inner class GetProducts {
        @DisplayName("상품마다 브랜드 정보와 구매 가능 여부가 함께 나간다 (P-10 · D-10).")
        @Test
        fun returnsProductsWithBrand() {
            // arrange
            val brand = brandId("루퍼스")
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "티셔츠", price = 3_500L))

            // act
            val item = get(LIST).body?.path("data")?.path("items")?.get(0)

            // assert
            assertAll(
                { assertThat(item?.path("name")?.asText()).isEqualTo("티셔츠") },
                { assertThat(item?.path("price")?.asLong()).isEqualTo(3_500L) },
                { assertThat(item?.path("brand")?.path("id")?.asLong()).isEqualTo(brand) },
                { assertThat(item?.path("brand")?.path("name")?.asText()).isEqualTo("루퍼스") },
                { assertThat(item?.path("purchasable")?.asBoolean()).isTrue() },
            )
        }

        /**
         * **구조로 막을 수 없는 자리다** (DS-5). `ProductInfo` 가 재고를 들고 있으므로 고객 DTO 가
         * 실수로 노출할 수 있다. 그 실수를 여기서 잡는다.
         */
        @DisplayName("고객 응답에 재고 수량·저장된 판매 상태·삭제 시각이 없다 (D-10 · DS-5).")
        @Test
        fun hidesAdminOnlyFields() {
            // arrange
            productJpaRepository.save(ProductFixture.product(brandId = brandId()))

            // act
            val item = get(LIST).body?.path("data")?.path("items")?.get(0)

            // assert
            assertAll(
                { assertThat(item?.has("stock")).isFalse() },
                { assertThat(item?.has("status")).isFalse() },
                { assertThat(item?.has("deletedAt")).isFalse() },
                { assertThat(item?.has("createdAt")).isFalse() },
                { assertThat(item?.has("updatedAt")).isFalse() },
            )
        }

        @DisplayName("판매중지·단종된 상품은 목록에 없다 (P-39).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun excludesNotOnSale(status: ProductStatus) {
            // arrange
            val brand = brandId()
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "판매중"))
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "안 팖", status = status))

            // act
            val data = get(LIST).body?.path("data")

            // assert
            assertAll(
                { assertThat(data?.path("items")?.map { it.path("name").asText() }).containsExactly("판매중") },
                { assertThat(data?.path("totalCount")?.asLong()).isEqualTo(1L) },
            )
        }

        @DisplayName("재고 0 인 상품은 목록에 남고, 구매 불가로 나간다 (P-37 · 설계 6-4절).")
        @Test
        fun keepsOutOfStockAsNotPurchasable() {
            // arrange
            productJpaRepository.save(ProductFixture.product(brandId = brandId(), stock = 0))

            // act
            val item = get(LIST).body?.path("data")?.path("items")?.get(0)

            // assert
            assertThat(item?.path("purchasable")?.asBoolean()).isFalse()
        }

        @DisplayName("삭제된 상품은 목록에 없다 (P-12).")
        @Test
        fun excludesDeleted() {
            // arrange
            val brand = brandId()
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "살아있음"))
            productJpaRepository.save(ProductFixture.deletedProduct(brandId = brand, name = "지워짐"))

            // act
            val items = get(LIST).body?.path("data")?.path("items")

            // assert
            assertThat(items?.map { it.path("name").asText() }).containsExactly("살아있음")
        }

        @DisplayName("브랜드로 걸러 볼 수 있다 (P-08).")
        @Test
        fun filtersByBrand() {
            // arrange
            val first = brandId("브랜드1")
            val second = brandId("브랜드2")
            productJpaRepository.save(ProductFixture.product(brandId = first, name = "브랜드1 상품"))
            productJpaRepository.save(ProductFixture.product(brandId = second, name = "브랜드2 상품"))

            // act
            val items = get("$LIST?brandId=$second").body?.path("data")?.path("items")

            // assert
            assertThat(items?.map { it.path("name").asText() }).containsExactly("브랜드2 상품")
        }

        @DisplayName("가격 낮은 순으로 볼 수 있다 (P-08).")
        @Test
        fun sortsByPriceAsc() {
            // arrange
            val brand = brandId()
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "비쌈", price = 9_000L))
            productJpaRepository.save(ProductFixture.product(brandId = brand, name = "쌈", price = 1_000L))

            // act
            val items = get("$LIST?sort=price_asc").body?.path("data")?.path("items")

            // assert
            assertThat(items?.map { it.path("name").asText() }).containsExactly("쌈", "비쌈")
        }

        @DisplayName("정렬 값이 규격 밖이면 INVALID_SORT 로 거절한다 (P-08).")
        @ParameterizedTest
        @ValueSource(strings = ["price_desc", "LATEST", "likes"])
        fun rejectsUnsupportedSort(sort: String) {
            // act
            val response = get("$LIST?sort=$sort")

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body?.path("meta")?.path("errorCode")?.asText()).isEqualTo(ErrorType.INVALID_SORT.code) },
            )
        }

        @DisplayName("페이지 값이 규격 밖이면 INVALID_PAGE 로 거절한다 (P-08).")
        @ParameterizedTest
        @ValueSource(strings = ["page=-1", "size=0", "size=101"])
        fun rejectsInvalidPage(query: String) {
            // act
            val response = get("$LIST?$query")

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body?.path("meta")?.path("errorCode")?.asText()).isEqualTo(ErrorType.INVALID_PAGE.code) },
            )
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    inner class GetProduct {
        @DisplayName("브랜드 정보와 판매 상태가 함께 나간다 (P-10 · C-3).")
        @Test
        fun returnsProductWithBrandAndSaleStatus() {
            // arrange
            val brand = brandId("루퍼스")
            val product = productJpaRepository.save(ProductFixture.product(brandId = brand, name = "티셔츠"))

            // act
            val data = get(DETAIL(product.id)).body?.path("data")

            // assert
            assertAll(
                { assertThat(data?.path("id")?.asLong()).isEqualTo(product.id) },
                { assertThat(data?.path("brand")?.path("name")?.asText()).isEqualTo("루퍼스") },
                { assertThat(data?.path("saleStatus")?.asText()).isEqualTo("ON_SALE") },
                { assertThat(data?.path("purchasable")?.asBoolean()).isTrue() },
            )
        }

        @DisplayName("재고 0 이면 판매 상태가 재고없음이다. 저장된 상태는 여전히 판매중이다 (P-37 · 설계 6-4절).")
        @Test
        fun derivesSoldOutFromStock() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId(), stock = 0))

            // act
            val data = get(DETAIL(product.id)).body?.path("data")

            // assert
            assertAll(
                { assertThat(data?.path("saleStatus")?.asText()).isEqualTo("SOLD_OUT") },
                { assertThat(data?.path("purchasable")?.asBoolean()).isFalse() },
                { assertThat(productJpaRepository.findById(product.id).get().status).isEqualTo(ProductStatus.ON_SALE) },
            )
        }

        @DisplayName("판매중지·단종된 상품도 상세로는 조회된다 (P-39). 목록에서만 빠진다.")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun returnsNotOnSaleProduct(status: ProductStatus) {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId(), status = status))

            // act
            val response = get(DETAIL(product.id))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body?.path("data")?.path("saleStatus")?.asText()).isEqualTo(status.name) },
                { assertThat(response.body?.path("data")?.path("purchasable")?.asBoolean()).isFalse() },
            )
        }

        @DisplayName("고객 응답에 재고 수량이 없다 (D-10 · DS-5).")
        @Test
        fun hidesStock() {
            // arrange
            val product = productJpaRepository.save(ProductFixture.product(brandId = brandId()))

            // act
            val data = get(DETAIL(product.id)).body?.path("data")

            // assert
            assertAll(
                { assertThat(data?.has("stock")).isFalse() },
                { assertThat(data?.has("deletedAt")).isFalse() },
                { assertThat(data?.has("status")).isFalse() },
            )
        }

        @DisplayName("삭제된 상품과 없는 상품은 같은 오류로 답한다 (P-04 · P-12).")
        @Test
        fun returnsSameErrorForDeletedAndAbsent() {
            // arrange
            val deleted = productJpaRepository.save(ProductFixture.deletedProduct(brandId = brandId()))

            // act
            val deletedResponse = get(DETAIL(deleted.id))
            val absentResponse = get(DETAIL(999_999L))

            // assert
            assertAll(
                { assertThat(deletedResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                {
                    assertThat(deletedResponse.body?.path("meta")?.path("errorCode")?.asText())
                        .isEqualTo(ErrorType.PRODUCT_NOT_FOUND.code)
                },
                {
                    assertThat(absentResponse.body?.path("meta")?.path("errorCode")?.asText())
                        .isEqualTo(deletedResponse.body?.path("meta")?.path("errorCode")?.asText())
                },
            )
        }
    }

    @DisplayName("상품 응답의 좋아요 수는,")
    @Nested
    inner class LikeCount {
        private fun like(productId: Long, userId: Long) {
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = userId, productId = productId))
        }

        @DisplayName("목록 한 줄에 함께 나간다 (P-10).")
        @Test
        fun appearsInList() {
            // arrange
            val productId = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId())).productId
            like(productId, userId = 1L)
            like(productId, userId = 2L)

            // act
            val item = get(LIST).body?.path("data")?.path("items")?.get(0)

            // assert
            assertThat(item?.path("likeCount")?.asLong()).isEqualTo(2L)
        }

        /** 관계에서 세므로(P-15) 아무도 안 누른 상품에도 답이 있다 — 저장된 값이 없다는 뜻이 아니다. */
        @DisplayName("아무도 안 눌렀으면 0 이다 (P-15).")
        @Test
        fun isZeroWithoutAnyLike() {
            // arrange
            productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId()))

            // act
            val item = get(LIST).body?.path("data")?.path("items")?.get(0)

            // assert
            assertThat(item?.path("likeCount")?.asLong()).isEqualTo(0L)
        }

        @DisplayName("상세에도 나간다 (P-10 · C-3).")
        @Test
        fun appearsInDetail() {
            // arrange
            val productId = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brandId())).productId
            like(productId, userId = 1L)

            // act
            val data = get(DETAIL(productId)).body?.path("data")

            // assert
            assertThat(data?.path("likeCount")?.asLong()).isEqualTo(1L)
        }

        @DisplayName("likes_desc 로 부르면 좋아요 많은 순이다 (P-08).")
        @Test
        fun sortsByLikeCount() {
            // arrange
            val brand = brandId()
            val quiet = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brand, name = "조용")).productId
            val popular = productJpaRepository.saveAndFlush(ProductFixture.product(brandId = brand, name = "인기")).productId
            like(quiet, userId = 1L)
            like(popular, userId = 1L)
            like(popular, userId = 2L)

            // act
            val names = get("$LIST?sort=likes_desc").body?.path("data")?.path("items")?.map { it.path("name").asText() }

            // assert
            assertThat(names).containsExactly("인기", "조용")
        }
    }
}
