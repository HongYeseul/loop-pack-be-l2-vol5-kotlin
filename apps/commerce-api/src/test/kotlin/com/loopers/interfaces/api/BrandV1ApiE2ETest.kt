package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.fixture.BrandFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
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
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * C-1 · 고객 브랜드 상세.
 *
 * 응답을 [JsonNode] 로 받는다. **응답에 없어야 할 키가 없는지**(D-10) 도 확인 대상이라,
 * 타입이 있는 DTO 로 받으면 그 키가 있든 없든 테스트가 통과해버린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val ENDPOINT: (Any) -> String = { id -> "/api/v1/brands/$id" }
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun get(url: String) = testRestTemplate.exchange(url, HttpMethod.GET, HttpEntity<Any>(Unit), JsonNode::class.java)

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    inner class Get {
        @DisplayName("살아 있는 브랜드는, id 와 이름을 돌려준다.")
        @Test
        fun returnsBrand_whenAlive() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand(name = "루퍼스"))

            // act
            val response = get(ENDPOINT(brand.id))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body?.path("meta")?.path("result")?.asText()).isEqualTo("SUCCESS") },
                { assertThat(response.body?.path("data")?.path("id")?.asLong()).isEqualTo(brand.id) },
                { assertThat(response.body?.path("data")?.path("name")?.asText()).isEqualTo("루퍼스") },
            )
        }

        @DisplayName("고객 응답에는 삭제 시각·생성 시각이 담기지 않는다 (D-10). 고객에게 필요한 것은 id 와 이름뿐이다.")
        @Test
        fun hidesAdminOnlyFields() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.brand())

            // act
            val data = get(ENDPOINT(brand.id)).body?.path("data")

            // assert
            assertAll(
                { assertThat(data?.has("deletedAt")).isFalse() },
                { assertThat(data?.has("createdAt")).isFalse() },
                { assertThat(data?.has("updatedAt")).isFalse() },
            )
        }

        @DisplayName("삭제된 브랜드는, BRAND_NOT_FOUND 로 답한다 (P-12).")
        @Test
        fun returnsBrandNotFound_whenDeleted() {
            // arrange
            val brand = brandJpaRepository.save(BrandFixture.deletedBrand())

            // act
            val response = get(ENDPOINT(brand.id))

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body?.path("meta")?.path("errorCode")?.asText()).isEqualTo(ErrorType.BRAND_NOT_FOUND.code) },
            )
        }

        @DisplayName("없는 브랜드는, 삭제된 것과 같은 오류로 답한다. 지워진 브랜드가 있었다는 사실을 드러내지 않는다.")
        @Test
        fun returnsSameErrorForAbsentAndDeleted() {
            // arrange
            val deleted = brandJpaRepository.save(BrandFixture.deletedBrand())

            // act
            val absentResponse = get(ENDPOINT(999_999L))
            val deletedResponse = get(ENDPOINT(deleted.id))

            // assert
            assertAll(
                { assertThat(absentResponse.statusCode).isEqualTo(deletedResponse.statusCode) },
                {
                    assertThat(absentResponse.body?.path("meta")?.path("errorCode")?.asText())
                        .isEqualTo(deletedResponse.body?.path("meta")?.path("errorCode")?.asText())
                },
            )
        }

        @DisplayName("숫자가 아닌 id 는, 형식 오류로 답한다 (DS-2).")
        @Test
        fun returnsBadRequest_whenIdIsNotNumeric() {
            assertThat(get(ENDPOINT("가나다")).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }
}
