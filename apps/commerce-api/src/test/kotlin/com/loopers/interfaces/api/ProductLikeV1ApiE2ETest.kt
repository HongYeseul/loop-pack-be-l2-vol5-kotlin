package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.user.UserStatus
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.like.ProductLikeJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.user.UserJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * C-4 · C-5 좋아요 등록·취소.
 *
 * **응답과 저장을 함께 본다.** P-14 의 기대값은 "응답 200" 이 아니라 "응답 200 **이면서 행이 1개**"다 —
 * 응답만 보면 멱등한 척하면서 행이 쌓이는 구현을 통과시킨다 (설계 6-4절).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductLikeV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val brandJpaRepository: BrandJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val userJpaRepository: UserJpaRepository,
    private val productLikeJpaRepository: ProductLikeJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val LIKES: (Any) -> String = { productId -> "/api/v1/products/$productId/likes" }
        private val MY_LIKES: (Any) -> String = { userId -> "/api/v1/users/$userId/likes" }
        private const val OTHER_LOGIN_ID = "user2"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun call(method: HttpMethod, productId: Any, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        testRestTemplate.exchange(
            LIKES(productId),
            method,
            HttpEntity<Any>(HttpHeaders().apply { loginId?.let { set("X-USER-ID", it) } }),
            JsonNode::class.java,
        )

    private fun like(productId: Any, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        call(HttpMethod.POST, productId, loginId)

    private fun unlike(productId: Any, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        call(HttpMethod.DELETE, productId, loginId)

    private fun user(loginId: String = UserFixture.DEFAULT_LOGIN_ID, status: UserStatus = UserStatus.ACTIVE) {
        userJpaRepository.save(UserFixture.user(loginId = loginId, status = status))
    }

    private fun product(status: ProductStatus = ProductStatus.ON_SALE, deleted: Boolean = false): Long {
        val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        val product = ProductFixture.product(brandId = brandId, status = status).apply { if (deleted) delete() }
        return productJpaRepository.save(product).id
    }

    private fun rowCount(): Long = productLikeJpaRepository.count()

    private fun JsonNode?.liked(): Boolean? = this?.path("data")?.path("liked")?.asBoolean()

    private fun JsonNode?.likeCount(): Long? = this?.path("data")?.path("likeCount")?.asLong()

    private fun JsonNode?.errorCode(): String? = this?.path("meta")?.path("errorCode")?.asText()

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    inner class Like {
        @DisplayName("등록하면, 최종 좋아요 여부와 상품의 좋아요 수가 함께 나온다 (P-31).")
        @Test
        fun returnsFinalState() {
            // arrange
            user()
            val productId = product()

            // act
            val response = like(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.liked()).isTrue() },
                { assertThat(response.body.likeCount()).isEqualTo(1L) },
            )
        }

        @DisplayName("같은 요청을 두 번 보내도, 응답은 같고 행은 하나다 (P-14 · D-9).")
        @Test
        fun isIdempotent() {
            // arrange
            user()
            val productId = product()

            // act
            like(productId)
            val second = like(productId)

            // assert
            assertAll(
                { assertThat(second.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(second.body.liked()).isTrue() },
                { assertThat(second.body.likeCount()).isEqualTo(1L) },
                { assertThat(rowCount()).isEqualTo(1L) },
            )
        }

        @DisplayName("사람마다 한 행이라, 둘이 누르면 좋아요 수가 둘이다 (P-15).")
        @Test
        fun countsEachUser() {
            // arrange
            user()
            user(OTHER_LOGIN_ID)
            val productId = product()

            // act
            like(productId)
            val second = like(productId, OTHER_LOGIN_ID)

            // assert
            assertAll(
                { assertThat(second.body.likeCount()).isEqualTo(2L) },
                { assertThat(rowCount()).isEqualTo(2L) },
            )
        }

        @DisplayName("삭제된 상품에는 새로 걸 수 없다 (P-16).")
        @Test
        fun rejectsDeletedProduct() {
            // arrange
            user()
            val productId = product(deleted = true)

            // act
            val response = like(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND.code) },
                { assertThat(rowCount()).isEqualTo(0L) },
            )
        }

        /**
         * P-16 이 막는 것은 **삭제**뿐이다. 판매 상태는 다른 축이라(DS-11) 단종된 상품도 카탈로그에 남고
         * 상세로 조회된다(P-39) — 볼 수 있는 상품을 좋아할 수 없을 이유가 없다.
         */
        @DisplayName("단종된 상품에는 걸 수 있다 — 삭제와 판매 상태는 다른 축이다 (DS-11).")
        @Test
        fun allowsDiscontinuedProduct() {
            // arrange
            user()
            val productId = product(status = ProductStatus.DISCONTINUED)

            // act
            val response = like(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.liked()).isTrue() },
            )
        }

        @DisplayName("헤더가 없으면 USER_NOT_IDENTIFIED 다. 저장소를 보지 않는다 (P-01 · DS-9).")
        @Test
        fun rejectsMissingHeader() {
            // arrange
            user()
            val productId = product()

            // act
            val response = like(productId, loginId = null)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_IDENTIFIED.code) },
                { assertThat(rowCount()).isEqualTo(0L) },
            )
        }

        @DisplayName("형식은 맞지만 없는 사용자면 USER_NOT_FOUND 다 — 고칠 대상이 헤더가 아니라 식별자다 (DS-8).")
        @Test
        fun rejectsUnknownUser() {
            // arrange
            val productId = product()

            // act
            val response = like(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_FOUND.code) },
                { assertThat(rowCount()).isEqualTo(0L) },
            )
        }

        @DisplayName("차단된 계정의 요청은 거절된다 (P-42).")
        @Test
        fun rejectsBlockedUser() {
            // arrange
            user(status = UserStatus.BLOCKED)
            val productId = product()

            // act
            val response = like(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_BLOCKED.code) },
                { assertThat(rowCount()).isEqualTo(0L) },
            )
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    inner class Unlike {
        @DisplayName("취소하면, 최종 상태가 나오고 행이 사라진다 (P-17 · P-31 · D-2).")
        @Test
        fun returnsFinalState() {
            // arrange
            user()
            val productId = product()
            like(productId)

            // act
            val response = unlike(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.liked()).isFalse() },
                { assertThat(response.body.likeCount()).isEqualTo(0L) },
                { assertThat(rowCount()).isEqualTo(0L) },
            )
        }

        @DisplayName("없는 관계를 취소해도 성공이다 (P-17).")
        @Test
        fun acceptsMissingRelation() {
            // arrange
            user()
            val productId = product()

            // act
            val response = unlike(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.liked()).isFalse() },
                { assertThat(response.body.likeCount()).isEqualTo(0L) },
            )
        }

        /** P-16 의 뒷면이다. 새로 거는 것은 막고 **이미 걸어둔 것은 거둘 수 있어야** 한다. */
        @DisplayName("상품이 삭제되어도, 이미 걸어둔 관계는 취소된다 (P-16).")
        @Test
        fun cancelsRelationOnDeletedProduct() {
            // arrange
            user()
            val productId = product()
            like(productId)
            productJpaRepository.findById(productId).get().let { productJpaRepository.saveAndFlush(it.apply { delete() }) }

            // act
            val response = unlike(productId)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.liked()).isFalse() },
                { assertThat(rowCount()).isEqualTo(0L) },
            )
        }

        @DisplayName("남의 관계는 지워지지 않는다 (P-02).")
        @Test
        fun leavesOtherUsersRelation() {
            // arrange
            user()
            user(OTHER_LOGIN_ID)
            val productId = product()
            like(productId, OTHER_LOGIN_ID)

            // act
            val response = unlike(productId)

            // assert
            assertAll(
                { assertThat(response.body.liked()).isFalse() },
                { assertThat(response.body.likeCount()).isEqualTo(1L) },
                { assertThat(rowCount()).isEqualTo(1L) },
            )
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    inner class MyLikes {
        private fun myLikes(
            path: String = UserFixture.DEFAULT_LOGIN_ID,
            loginId: String? = UserFixture.DEFAULT_LOGIN_ID,
            query: String = "",
        ) = testRestTemplate.exchange(
            MY_LIKES(path) + query,
            HttpMethod.GET,
            HttpEntity<Any>(HttpHeaders().apply { loginId?.let { set("X-USER-ID", it) } }),
            JsonNode::class.java,
        )

        private fun JsonNode?.names(): List<String> =
            this?.path("data")?.path("items")?.map { it.path("name").asText() } ?: emptyList()

        @DisplayName("내가 좋아요한 상품이 브랜드 정보와 함께 나온다 (P-10 · C-6).")
        @Test
        fun returnsLikedProductsWithBrand() {
            // arrange
            user()
            val brandId = brandJpaRepository.save(BrandFixture.brand("루퍼스")).brandId
            val productId = productJpaRepository.save(
                ProductFixture.product(brandId = brandId, name = "티셔츠", price = 3_500L),
            ).productId
            like(productId)

            // act
            val item = myLikes().body?.path("data")?.path("items")?.get(0)

            // assert
            assertAll(
                { assertThat(item?.path("name")?.asText()).isEqualTo("티셔츠") },
                { assertThat(item?.path("price")?.asLong()).isEqualTo(3_500L) },
                { assertThat(item?.path("brand")?.path("name")?.asText()).isEqualTo("루퍼스") },
            )
        }

        @DisplayName("최근에 좋아요한 것이 먼저다 (P-45).")
        @Test
        fun ordersByMostRecentlyLiked() {
            // arrange
            user()
            val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
            val first = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "먼저 누름")).productId
            val second = productJpaRepository.save(ProductFixture.product(brandId = brandId, name = "나중 누름")).productId
            like(first)
            like(second)

            // act & assert
            assertThat(myLikes().body.names()).containsExactly("나중 누름", "먼저 누름")
        }

        @DisplayName("상품이 삭제되면 내 목록에서 사라진다. 관계는 남아 있다 (P-45 · P-16).")
        @Test
        fun excludesDeletedProduct() {
            // arrange
            user()
            val productId = product()
            like(productId)
            productJpaRepository.findById(productId).get().let { productJpaRepository.saveAndFlush(it.apply { delete() }) }

            // act
            val response = myLikes()

            // assert
            assertAll(
                { assertThat(response.body.names()).isEmpty() },
                { assertThat(response.body?.path("data")?.path("totalCount")?.asLong()).isEqualTo(0L) },
                { assertThat(rowCount()).isEqualTo(1L) },
            )
        }

        /** 권한 오류로 답하면 "그 사용자는 있다" 가 새어나간다. 요청자가 할 수 있는 일은 어느 쪽이든 같다. */
        @DisplayName("남의 목록을 보려 하면, 권한 오류가 아니라 없는 대상 오류다 (P-02).")
        @Test
        fun hidesOtherUsersList() {
            // arrange
            user()
            user(OTHER_LOGIN_ID)

            // act
            val response = myLikes(path = OTHER_LOGIN_ID)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_FOUND.code) },
            )
        }

        @DisplayName("헤더가 없으면 USER_NOT_IDENTIFIED 다 (P-01).")
        @Test
        fun rejectsMissingHeader() {
            // arrange
            user()

            // act
            val response = myLikes(loginId = null)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_IDENTIFIED.code) },
            )
        }

        @DisplayName("페이지가 규격을 벗어나면 거절한다 (P-08 · 설계 6-1절).")
        @Test
        fun rejectsInvalidPage() {
            // arrange
            user()

            // act
            val response = myLikes(query = "?size=0")

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.INVALID_PAGE.code) },
            )
        }
    }
}
