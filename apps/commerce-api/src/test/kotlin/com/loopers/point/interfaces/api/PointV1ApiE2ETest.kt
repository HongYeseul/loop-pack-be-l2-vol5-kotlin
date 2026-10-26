package com.loopers.point.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.point.domain.Point
import com.loopers.user.domain.UserStatus
import com.loopers.fixture.UserFixture
import com.loopers.point.infrastructure.PointJpaRepository
import com.loopers.point.infrastructure.PointTransactionJpaRepository
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.support.error.ErrorType
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/** 거절 케이스는 응답뿐 아니라 잔액·원장까지 확인 (반쯤 올려둔 구현 차단) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val userJpaRepository: UserJpaRepository,
    private val pointJpaRepository: PointJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val CHARGE = "/api/v1/points/charge"
        private const val BALANCE = "/api/v1/points"
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun headers(loginId: String?) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        loginId?.let { set("X-USER-ID", it) }
    }

    private fun charge(body: String, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        testRestTemplate.exchange(CHARGE, HttpMethod.POST, HttpEntity(body, headers(loginId)), JsonNode::class.java)

    private fun chargeAmount(amount: Long, loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        charge("""{"amount": $amount}""", loginId)

    private fun balance(loginId: String? = UserFixture.DEFAULT_LOGIN_ID) =
        testRestTemplate.exchange(BALANCE, HttpMethod.GET, HttpEntity<Any>(headers(loginId)), JsonNode::class.java)

    private fun user(loginId: String = UserFixture.DEFAULT_LOGIN_ID, status: UserStatus = UserStatus.ACTIVE): Long =
        userJpaRepository.save(UserFixture.user(loginId = loginId, status = status)).userId

    private fun JsonNode?.balanceValue(): Long? = this?.path("data")?.path("balance")?.asLong()

    private fun JsonNode?.errorCode(): String? = this?.path("meta")?.path("errorCode")?.asText()

    private fun storedBalance(userId: Long): Long? = pointJpaRepository.findByUserId(userId)?.balance

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    inner class Charge {
        @DisplayName("충전하면, 충전 후 잔액이 나온다.")
        @Test
        fun returnsBalanceAfterCharge() {
            // arrange
            val userId = user()

            // act
            val response = chargeAmount(10_000L)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.balanceValue()).isEqualTo(10_000L) },
                { assertThat(storedBalance(userId)).isEqualTo(10_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(1L) },
            )
        }

        @DisplayName("여러 번 충전하면 쌓인다.")
        @Test
        fun accumulates() {
            // arrange
            user()

            // act
            chargeAmount(10_000L)
            val second = chargeAmount(5_000L)

            // assert
            assertAll(
                { assertThat(second.body.balanceValue()).isEqualTo(15_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(2L) },
            )
        }

        /** 0원 충전은 변경 없이 거절 (성공으로 답하면 충전된 줄 오해) */
        @DisplayName("0·음수·1회 한도 초과는 거절하고, 잔액도 원장도 그대로다.")
        @ParameterizedTest
        @ValueSource(longs = [0L, -1L, 1_000_001L])
        fun rejectsInvalidAmount(amount: Long) {
            // arrange
            val userId = user()
            chargeAmount(1_000L)

            // act
            val response = chargeAmount(amount)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.CHARGE_AMOUNT_INVALID.code) },
                { assertThat(storedBalance(userId)).isEqualTo(1_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(1L) },
            )
        }

        @DisplayName("충전 결과가 잔액 상한을 넘으면 거절하고, 잔액은 그대로다.")
        @Test
        fun rejectsWhenResultExceedsBalanceLimit() {
            // arrange
            val userId = user()
            pointJpaRepository.saveAndFlush(Point(userId = userId, balance = Point.MAX_BALANCE))

            // act
            val response = chargeAmount(1L)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.BALANCE_LIMIT_EXCEEDED.code) },
                { assertThat(storedBalance(userId)).isEqualTo(Point.MAX_BALANCE) },
                { assertThat(pointTransactionJpaRepository.count()).isZero() },
            )
        }

        /** 형식은 interfaces 가 검사, 숫자가 아닌 값과 0원은 요청자 대응이 다름 */
        @DisplayName("amount 가 없거나 숫자가 아니면 형식 오류다.")
        @ParameterizedTest
        @ValueSource(strings = ["{}", """{"amount": "천원"}""", """{"amount": null}"""])
        fun rejectsMalformedBody(body: String) {
            // arrange
            user()

            // act
            val response = charge(body)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.BAD_REQUEST.code) },
                { assertThat(pointTransactionJpaRepository.count()).isZero() },
            )
        }

        @DisplayName("헤더가 없으면 USER_NOT_IDENTIFIED 다. 저장소를 보지 않는다.")
        @Test
        fun rejectsMissingHeader() {
            // arrange
            user()

            // act
            val response = chargeAmount(1_000L, loginId = null)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_IDENTIFIED.code) },
                { assertThat(pointJpaRepository.count()).isZero() },
            )
        }

        @DisplayName("없는 사용자면 USER_NOT_FOUND 다.")
        @Test
        fun rejectsUnknownUser() {
            // act
            val response = chargeAmount(1_000L)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_FOUND.code) },
                { assertThat(pointJpaRepository.count()).isZero() },
            )
        }

        @DisplayName("차단된 계정의 요청은 거절된다.")
        @Test
        fun rejectsBlockedUser() {
            // arrange
            user(status = UserStatus.BLOCKED)

            // act
            val response = chargeAmount(1_000L)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_BLOCKED.code) },
                { assertThat(pointJpaRepository.count()).isZero() },
            )
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    inner class Balance {
        /** 잔액 0원은 허용 상태, 충전 0원 거절과 같은 숫자지만 의미가 다름 */
        @DisplayName("한 번도 충전하지 않았으면 0 이고, 조회가 행을 만들지 않는다.")
        @Test
        fun returnsZeroWithoutCreatingRow() {
            // arrange
            user()

            // act
            val response = balance()

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body.balanceValue()).isZero() },
                { assertThat(pointJpaRepository.count()).isZero() },
            )
        }

        @DisplayName("충전한 값을 그대로 돌려준다.")
        @Test
        fun returnsStoredBalance() {
            // arrange
            user()
            chargeAmount(7_000L)

            // act & assert
            assertThat(balance().body.balanceValue()).isEqualTo(7_000L)
        }

        @DisplayName("남의 잔액은 보이지 않는다.")
        @Test
        fun showsOnlyOwnBalance() {
            // arrange
            user()
            user("user2")
            chargeAmount(7_000L, loginId = "user2")

            // act & assert
            assertThat(balance().body.balanceValue()).isZero()
        }

        @DisplayName("헤더가 없으면 USER_NOT_IDENTIFIED 다.")
        @Test
        fun rejectsMissingHeader() {
            // arrange
            user()

            // act
            val response = balance(loginId = null)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_IDENTIFIED.code) },
            )
        }

        @DisplayName("없는 사용자면 USER_NOT_FOUND 다.")
        @Test
        fun rejectsUnknownUser() {
            // act
            val response = balance()

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(response.body.errorCode()).isEqualTo(ErrorType.USER_NOT_FOUND.code) },
            )
        }
    }
}
