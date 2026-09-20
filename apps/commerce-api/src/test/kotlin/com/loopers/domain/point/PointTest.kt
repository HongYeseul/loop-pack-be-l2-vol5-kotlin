package com.loopers.domain.point

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 충전액 검사와 상한 검사를 **잔액을 아는 객체**가 한다 (설계 3절 · P-19 · P-20).
 *
 * 거절은 "안 된다고 답하는 것" 이 아니라 **아무것도 하지 않는 것** 이다 (P-21).
 * 그래서 모든 거절 케이스가 잔액이 그대로인지까지 본다 — 예외만 확인하면
 * 반쯤 더하고 터지는 구현도 통과한다.
 */
class PointTest {
    private fun point(balance: Long = 0L) = Point(userId = 1L, balance = balance)

    @DisplayName("포인트를 충전할 때,")
    @Nested
    inner class Charge {
        @DisplayName("충전액만큼 잔액이 는다 (P-18).")
        @Test
        fun increasesBalance() {
            // arrange
            val point = point(balance = 1_000L)

            // act
            point.charge(500L)

            // assert
            assertThat(point.balance).isEqualTo(1_500L)
        }

        @DisplayName("0 이하면 거절하고, 잔액은 그대로다 (P-19 · P-21).")
        @ParameterizedTest
        @ValueSource(longs = [0L, -1L, -1_000L])
        fun rejectsNonPositiveAmount(amount: Long) {
            // arrange
            val point = point(balance = 1_000L)

            // act
            val exception = assertThrows<CoreException> { point.charge(amount) }

            // assert · 0원을 충전하겠다는 요청은 아무것도 바꾸지 않는다. 성공으로 답하면 "충전됐다" 고 오해한다
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.CHARGE_AMOUNT_INVALID) },
                { assertThat(point.balance).isEqualTo(1_000L) },
            )
        }

        @DisplayName("1회 충전 한도를 넘으면 거절하고, 잔액은 그대로다 (D-5).")
        @Test
        fun rejectsAmountOverChargeLimit() {
            // arrange
            val point = point(balance = 1_000L)

            // act
            val exception = assertThrows<CoreException> { point.charge(Point.MAX_CHARGE + 1) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.CHARGE_AMOUNT_INVALID) },
                { assertThat(point.balance).isEqualTo(1_000L) },
            )
        }

        /** 1회 한도와 다른 거절이다 (DS-8). 금액을 줄이면 되는 것과, 이미 가진 잔액 때문에 안 되는 것은 다르다. */
        @DisplayName("충전 결과가 잔액 상한을 넘으면 거절하고, 잔액은 그대로다 (P-20 · P-21).")
        @Test
        fun rejectsWhenResultExceedsBalanceLimit() {
            // arrange
            val point = point(balance = Point.MAX_BALANCE)

            // act
            val exception = assertThrows<CoreException> { point.charge(1L) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.BALANCE_LIMIT_EXCEEDED) },
                { assertThat(point.balance).isEqualTo(Point.MAX_BALANCE) },
            )
        }

        @DisplayName("상한에 정확히 닿는 충전은 된다 (P-20 · 경계).")
        @Test
        fun acceptsChargeUpToLimit() {
            // arrange
            val point = point(balance = Point.MAX_BALANCE - Point.MAX_CHARGE)

            // act
            point.charge(Point.MAX_CHARGE)

            // assert
            assertThat(point.balance).isEqualTo(Point.MAX_BALANCE)
        }
    }

    @DisplayName("포인트를 만들 때,")
    @Nested
    inner class Created {
        @DisplayName("잔액은 0 에서 시작한다. 0 은 허용되는 상태다 (P-20).")
        @Test
        fun startsFromZero() {
            assertThat(Point(userId = 1L).balance).isZero()
        }

        @DisplayName("음수 잔액으로는 만들 수 없다 (설계 7-1절 · balance >= 0).")
        @Test
        fun rejectsNegativeBalance() {
            assertThrows<CoreException> { Point(userId = 1L, balance = -1L) }
        }
    }
}
