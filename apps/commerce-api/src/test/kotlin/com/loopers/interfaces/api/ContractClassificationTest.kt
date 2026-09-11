package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.domain.example.ExampleModel
import com.loopers.infrastructure.example.ExampleJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

/**
 * 현재 `/api/v1/examples` 가 네 가지 입력에 대해 실제로 무엇을 돌려주는지 관찰한다.
 *
 * 새 기능을 만드는 테스트가 아니라, 이미 동작하는 API 의 오류 계약을 기록하는 테스트다.
 * 여기서 얻은 status / meta.result / meta.errorCode / data 네 값을
 * `docs/week1/order-discount-contract.md` 의 관찰 표에 그대로 옮긴다.
 *
 * 응답을 [JsonNode] 로 받는 이유: `ApiResponse.meta` 는 non-null 이므로,
 * 응답이 우리 봉투 규격을 벗어나면 역직렬화 단계에서 터져 관찰 자체가 불가능해진다.
 * "응답이 규격을 지키는가" 도 관찰 대상이라 파서가 규격을 미리 가정하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractClassificationTest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val exampleJpaRepository: ExampleJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val ENDPOINT_GET: (Any) -> String = { id: Any -> "/api/v1/examples/$id" }
        private const val UNMAPPED_URL = "/api/v1/not-mapped"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    /** 하나의 요청에서 계약 판단에 필요한 네 값만 뽑아낸다. */
    private data class Observation(
        val status: HttpStatusCode,
        val result: String?,
        val errorCode: String?,
        val hasData: Boolean,
    )

    private fun observe(requestUrl: String): Observation {
        val response = testRestTemplate.exchange(requestUrl, HttpMethod.GET, HttpEntity<Any>(Unit), JsonNode::class.java)
        val body = response.body
        return Observation(
            status = response.statusCode,
            result = body?.path("meta")?.path("result")?.takeIf { it.isTextual }?.asText(),
            errorCode = body?.path("meta")?.path("errorCode")?.takeIf { it.isTextual }?.asText(),
            hasData = body?.hasNonNull("data") ?: false,
        )
    }

    @DisplayName("입력 1 · 존재하는 숫자 ID : 정상 조회로 응답하고, 요청자는 응답을 그대로 사용한다.")
    @Test
    fun classifiesAsSuccess_whenIdExists() {
        // arrange
        val exampleModel = exampleJpaRepository.save(ExampleModel(name = "예시 제목", description = "예시 설명"))

        // act
        val observation = observe(ENDPOINT_GET(exampleModel.id))

        // assert
        assertAll(
            { assertThat(observation.status).isEqualTo(HttpStatus.OK) },
            { assertThat(observation.result).isEqualTo("SUCCESS") },
            { assertThat(observation.errorCode).isNull() },
            { assertThat(observation.hasData).isTrue() },
        )
    }

    @DisplayName("입력 2 · 숫자가 아닌 ID : 문법 오류로 응답하고, 요청자는 입력을 고친다.")
    @Test
    fun classifiesAsBadRequest_whenIdIsNotNumeric() {
        // act
        val observation = observe(ENDPOINT_GET("abc"))

        // assert
        assertAll(
            { assertThat(observation.status).isEqualTo(HttpStatus.BAD_REQUEST) },
            { assertThat(observation.result).isEqualTo("FAIL") },
            { assertThat(observation.errorCode).isEqualTo("Bad Request") },
            { assertThat(observation.hasData).isFalse() },
        )
    }

    @DisplayName("입력 3 · 존재하지 않는 숫자 ID : 대상 자원 없음으로 응답하고, 요청자는 다시 조회하거나 대체 흐름으로 간다.")
    @Test
    fun classifiesAsNotFound_whenIdDoesNotExist() {
        // act
        val observation = observe(ENDPOINT_GET(-1L))

        // assert
        assertAll(
            { assertThat(observation.status).isEqualTo(HttpStatus.NOT_FOUND) },
            { assertThat(observation.result).isEqualTo("FAIL") },
            { assertThat(observation.errorCode).isEqualTo("Not Found") },
            { assertThat(observation.hasData).isFalse() },
        )
    }

    @DisplayName("입력 4 · 매핑되지 않은 URL : 요청 처리기 없음으로 응답하고, 요청자는 경로를 고친다.")
    @Test
    fun classifiesAsNoHandler_whenUrlIsNotMapped() {
        // act
        val observation = observe(UNMAPPED_URL)

        // assert
        assertAll(
            { assertThat(observation.status).isEqualTo(HttpStatus.NOT_FOUND) },
            { assertThat(observation.result).isEqualTo("FAIL") },
            { assertThat(observation.errorCode).isEqualTo("Not Found") },
            { assertThat(observation.hasData).isFalse() },
        )
    }

    @DisplayName("입력 3 과 입력 4 는 요청자가 고쳐야 할 대상이 다르지만, 현재 응답으로 구분할 수 있는지 확인한다.")
    @Test
    fun comparesNotFoundOfMissingResourceAndUnmappedUrl() {
        // act
        val missingResource = observe(ENDPOINT_GET(-1L))
        val unmappedUrl = observe(UNMAPPED_URL)

        // assert
        assertAll(
            { assertThat(missingResource.status).isEqualTo(unmappedUrl.status) },
            { assertThat(missingResource.errorCode).isEqualTo(unmappedUrl.errorCode) },
        )
    }
}
