package com.loopers.domain.point

import com.loopers.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

/**
 * 잔액 변경과 원장 기록을 **함께** 하는 자리 (DS-12).
 *
 * `Point` 혼자 끝내지 못하는 유일한 규칙이라 서비스가 조립한다. 여기서 확인하는 것은
 * 두 가지가 **같이 일어나거나 같이 안 일어나는지** 다 (P-21 · P-40).
 */
class PointServiceTest {
    private class FakePointRepository : PointRepository {
        private val stored = linkedMapOf<Long, Point>()

        override fun save(point: Point): Point = point.also { stored[it.userId] = it }

        override fun findByUserId(userId: Long): Point? = stored[userId]

        val rowCount: Int get() = stored.size
    }

    private class FakePointTransactionRepository : PointTransactionRepository {
        val saved = mutableListOf<PointTransaction>()

        override fun save(transaction: PointTransaction): PointTransaction = transaction.also { saved += it }
    }

    private val pointRepository = FakePointRepository()
    private val pointTransactionRepository = FakePointTransactionRepository()
    private val pointService = PointService(pointRepository, pointTransactionRepository)

    @DisplayName("충전할 때,")
    @Nested
    inner class Charge {
        @DisplayName("한 번도 충전한 적 없으면, 잔액 행을 만들어 올린다 (P-18).")
        @Test
        fun createsRowOnFirstCharge() {
            // act
            val point = pointService.charge(userId = 1L, amount = 1_000L)

            // assert
            assertAll(
                { assertThat(point.balance).isEqualTo(1_000L) },
                { assertThat(pointRepository.findByUserId(1L)?.balance).isEqualTo(1_000L) },
            )
        }

        @DisplayName("충전마다 원장이 한 줄 남는다. 줄에는 충전 후 잔액이 함께 있다 (P-40).")
        @Test
        fun recordsLedgerRowPerCharge() {
            // act
            pointService.charge(userId = 1L, amount = 1_000L)
            pointService.charge(userId = 1L, amount = 500L)

            // assert
            assertAll(
                { assertThat(pointTransactionRepository.saved).hasSize(2) },
                { assertThat(pointTransactionRepository.saved.map { it.amount }).containsExactly(1_000L, 500L) },
                { assertThat(pointTransactionRepository.saved.map { it.balanceAfter }).containsExactly(1_000L, 1_500L) },
                { assertThat(pointTransactionRepository.saved.map { it.type }).containsOnly(PointTransactionType.CHARGE) },
            )
        }

        /** 잔액만 지키고 원장을 남기면 `balance == SUM(transactions)` 가 깨진다. 둘은 같이 움직인다. */
        @DisplayName("거절되면 잔액도 원장도 그대로다 (P-21 · P-40).")
        @Test
        fun leavesNothingBehindWhenRejected() {
            // arrange
            pointService.charge(userId = 1L, amount = 1_000L)

            // act
            assertThrows<CoreException> { pointService.charge(userId = 1L, amount = 0L) }

            // assert
            assertAll(
                { assertThat(pointRepository.findByUserId(1L)?.balance).isEqualTo(1_000L) },
                { assertThat(pointTransactionRepository.saved).hasSize(1) },
            )
        }

        @DisplayName("잔액은 사용자마다 따로다 (P-02).")
        @Test
        fun keepsBalancePerUser() {
            // act
            pointService.charge(userId = 1L, amount = 1_000L)
            pointService.charge(userId = 2L, amount = 300L)

            // assert
            assertAll(
                { assertThat(pointService.getBalance(1L)).isEqualTo(1_000L) },
                { assertThat(pointService.getBalance(2L)).isEqualTo(300L) },
            )
        }
    }

    @DisplayName("잔액을 조회할 때,")
    @Nested
    inner class GetBalance {
        /** 한 번도 충전하지 않은 것과 0원인 것은 사용자에게 같은 상태다. 조회가 행을 만들 이유가 없다. */
        @DisplayName("행이 없으면 0 이고, 행을 만들지도 않는다 (P-20 · P-22).")
        @Test
        fun returnsZeroWithoutCreatingRow() {
            // act
            val balance = pointService.getBalance(1L)

            // assert
            assertAll(
                { assertThat(balance).isZero() },
                { assertThat(pointRepository.rowCount).isZero() },
            )
        }

        @DisplayName("충전한 값을 그대로 돌려준다 (P-22).")
        @Test
        fun returnsStoredBalance() {
            // arrange
            pointService.charge(userId = 1L, amount = 7_000L)

            // act & assert
            assertThat(pointService.getBalance(1L)).isEqualTo(7_000L)
        }
    }
}
