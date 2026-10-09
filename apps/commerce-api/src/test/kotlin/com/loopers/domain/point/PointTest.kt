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

/** 거절 케이스는 예외뿐 아니라 잔액 불변까지 확인 (반쯤 더하고 터지는 구현 방지) */
class PointTest {
    private fun point(balance: Long = 0L) = Point(userId = 1L, balance = balance)

    @DisplayName("포인트를 충전할 때,")
    @Nested
    inner class Charge {
        @DisplayName("충전액만큼 잔액이 는다.")
        @Test
        fun increasesBalance() {
            // arrange
            val point = point(balance = 1_000L)

            // act
            point.charge(500L)

            // assert
            assertThat(point.balance).isEqualTo(1_500L)
        }

        @DisplayName("0 이하면 거절하고, 잔액은 그대로다.")
        @ParameterizedTest
        @ValueSource(longs = [0L, -1L, -1_000L])
        fun rejectsNonPositiveAmount(amount: Long) {
            // arrange
            val point = point(balance = 1_000L)

            // act
            val exception = assertThrows<CoreException> { point.charge(amount) }

            // assert · 0원 충전 요청은 변경 없이 거절
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.CHARGE_AMOUNT_INVALID) },
                { assertThat(point.balance).isEqualTo(1_000L) },
            )
        }

        @DisplayName("1회 충전 한도를 넘으면 거절하고, 잔액은 그대로다.")
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

        /** 1회 한도 초과와 오류 분리 (금액을 줄이면 되는 경우와 잔액 때문에 불가한 경우) */
        @DisplayName("충전 결과가 잔액 상한을 넘으면 거절하고, 잔액은 그대로다.")
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

        @DisplayName("상한에 정확히 닿는 충전은 된다 (경계).")
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

    @DisplayName("포인트를 쓸 때,")
    @Nested
    inner class Use {
        @DisplayName("쓴 만큼 잔액이 준다.")
        @Test
        fun decreasesBalance() {
            // arrange
            val point = point(balance = 10_000L)

            // act
            point.use(7_000L)

            // assert
            assertThat(point.balance).isEqualTo(3_000L)
        }

        @DisplayName("잔액보다 많이 쓰려 하면 거절하고, 잔액은 그대로다.")
        @Test
        fun rejectsAndKeepsBalance_whenAmountExceedsBalance() {
            // arrange
            val point = point(balance = 1_000L)

            // act
            val exception = assertThrows<CoreException> { point.use(1_001L) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.INSUFFICIENT_BALANCE) },
                { assertThat(point.balance).isEqualTo(1_000L) },
            )
        }

        @DisplayName("잔액을 정확히 다 쓰면 0 이 된다 (경계).")
        @Test
        fun allowsExactBalance() {
            // arrange
            val point = point(balance = 1_000L)

            // act
            point.use(1_000L)

            // assert
            assertThat(point.balance).isZero()
        }

        /** 충전 0 은 거절, 사용 0 은 허용 (0원 확정도 물건이 나감) */
        @DisplayName("0원은 잔액이 0이어도 쓸 수 있다.")
        @Test
        fun allowsZeroFromEmptyBalance() {
            // arrange
            val point = point(balance = 0L)

            // act
            point.use(0L)

            // assert
            assertThat(point.balance).isZero()
        }

        @DisplayName("음수는 쓸 수 없다. 뺄셈이 덧셈이 되면 충전 경로를 우회한다.")
        @ParameterizedTest
        @ValueSource(longs = [-1L, -1_000L])
        fun rejectsNegativeAmount(amount: Long) {
            // arrange
            val point = point(balance = 1_000L)

            // act
            val exception = assertThrows<CoreException> { point.use(amount) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.BAD_REQUEST) },
                { assertThat(point.balance).isEqualTo(1_000L) },
            )
        }
    }

    @DisplayName("포인트를 만들 때,")
    @Nested
    inner class Created {
        @DisplayName("잔액은 0 에서 시작한다. 0 은 허용되는 상태다.")
        @Test
        fun startsFromZero() {
            assertThat(Point(userId = 1L).balance).isZero()
        }

        @DisplayName("음수 잔액으로는 만들 수 없다 (balance >= 0).")
        @Test
        fun rejectsNegativeBalance() {
            assertThrows<CoreException> { Point(userId = 1L, balance = -1L) }
        }
    }
}
