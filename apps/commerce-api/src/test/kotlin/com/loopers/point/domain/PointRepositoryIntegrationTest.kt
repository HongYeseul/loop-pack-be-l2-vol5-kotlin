package com.loopers.point.domain

import com.loopers.point.infrastructure.PointJpaRepository
import com.loopers.point.infrastructure.PointTransactionJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.junit.jupiter.api.assertThrows

/** 저장 제약과 원장 불변식을 DB 에서 확인 (두 테이블이 같은 트랜잭션에서 움직이는지가 대상) */
@SpringBootTest
class PointRepositoryIntegrationTest @Autowired constructor(
    private val pointService: PointService,
    private val pointRepository: PointRepository,
    private val pointJpaRepository: PointJpaRepository,
    private val pointTransactionJpaRepository: PointTransactionJpaRepository,
    private val entityManager: EntityManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val USER_ID = 1L
        private const val ORDER_ID = 42L
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    /** 원장의 합, amount 는 크기라 type 으로 방향을 되살려 부호 적용 */
    private fun ledgerSum(userId: Long = USER_ID): Long =
        (
            entityManager
                .createNativeQuery(
                    """
                    SELECT COALESCE(SUM(CASE WHEN type = 'CHARGE' THEN amount ELSE -amount END), 0)
                    FROM point_transaction WHERE user_id = :userId
                    """,
                )
                .setParameter("userId", userId)
                .singleResult as Number
            ).toLong()

    @DisplayName("한 사용자의 잔액 행은,")
    @Nested
    inner class OneRowPerUser {
        @DisplayName("두 행이 될 수 없다 (UNIQUE(user_id)).")
        @Test
        fun isRejectedByConstraint() {
            // arrange
            pointJpaRepository.saveAndFlush(Point(userId = USER_ID))

            // act & assert
            assertThrows<DataIntegrityViolationException> {
                pointJpaRepository.saveAndFlush(Point(userId = USER_ID))
            }
        }

        @DisplayName("사용자가 다르면 막지 않는다.")
        @Test
        fun allowsDifferentUsers() {
            // act
            pointJpaRepository.saveAndFlush(Point(userId = USER_ID))
            pointJpaRepository.saveAndFlush(Point(userId = USER_ID + 1))

            // assert
            assertThat(pointJpaRepository.count()).isEqualTo(2L)
        }
    }

    @DisplayName("충전하고 나면,")
    @Nested
    inner class AfterCharge {
        /** 이 불변식이 깨지면 원장이 진실의 출처라는 전제가 무너짐 */
        @DisplayName("잔액이 원장의 합과 같다.")
        @Test
        fun keepsBalanceEqualToLedgerSum() {
            // act
            pointService.charge(USER_ID, 10_000L)
            pointService.charge(USER_ID, 5_000L)

            // assert
            assertAll(
                { assertThat(pointRepository.findByUserId(USER_ID)?.balance).isEqualTo(15_000L) },
                { assertThat(ledgerSum()).isEqualTo(15_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(2L) },
            )
        }

        @DisplayName("거절된 충전은 원장에도 잔액에도 남지 않는다.")
        @Test
        fun leavesNothingBehindWhenRejected() {
            // arrange
            pointService.charge(USER_ID, 10_000L)

            // act
            assertThrows<Exception> { pointService.charge(USER_ID, 0L) }

            // assert
            assertAll(
                { assertThat(pointRepository.findByUserId(USER_ID)?.balance).isEqualTo(10_000L) },
                { assertThat(ledgerSum()).isEqualTo(10_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(1L) },
            )
        }

        @DisplayName("원장 한 줄이 컬럼에 그대로 들어간다.")
        @Test
        fun storesLedgerRowAsWritten() {
            // arrange
            pointService.charge(USER_ID, 10_000L)

            // act
            val row = entityManager
                .createNativeQuery("SELECT user_id, type, amount, balance_after FROM point_transaction")
                .singleResult as Array<*>

            // assert · type 이 varchar 라 이름 그대로 저장
            assertAll(
                { assertThat((row[0] as Number).toLong()).isEqualTo(USER_ID) },
                { assertThat(row[1]).isEqualTo("CHARGE") },
                { assertThat((row[2] as Number).toLong()).isEqualTo(10_000L) },
                { assertThat((row[3] as Number).toLong()).isEqualTo(10_000L) },
            )
        }
    }

    @DisplayName("주문 확정으로 쓰고 나면,")
    @Nested
    inner class AfterUse {
        @DisplayName("잔액이 3,000 이고 원장 두 줄의 합과 같다.")
        @Test
        fun keepsBalanceEqualToLedgerSum() {
            // arrange
            pointService.charge(USER_ID, 10_000L)

            // act
            pointService.use(userId = USER_ID, amount = 7_000L, orderId = ORDER_ID)

            // assert
            assertAll(
                { assertThat(pointRepository.findByUserId(USER_ID)?.balance).isEqualTo(3_000L) },
                { assertThat(ledgerSum()).isEqualTo(3_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(2L) },
            )
        }

        @DisplayName("사용 줄에 주문 식별자가 함께 들어간다.")
        @Test
        fun storesOrderIdOnUseRow() {
            // arrange
            pointService.charge(USER_ID, 10_000L)

            // act
            pointService.use(userId = USER_ID, amount = 7_000L, orderId = ORDER_ID)

            // assert · user_id·order_id 가 모두 Long 이라 뒤바뀌어도 컴파일됨
            val row = entityManager
                .createNativeQuery("SELECT user_id, type, amount, order_id FROM point_transaction WHERE type = 'USE'")
                .singleResult as Array<*>
            assertAll(
                { assertThat((row[0] as Number).toLong()).isEqualTo(USER_ID) },
                { assertThat(row[1]).isEqualTo("USE") },
                { assertThat((row[2] as Number).toLong()).isEqualTo(7_000L) },
                { assertThat((row[3] as Number).toLong()).isEqualTo(ORDER_ID) },
            )
        }

        @DisplayName("거절된 결제는 원장에도 잔액에도 남지 않는다.")
        @Test
        fun leavesNothingBehindWhenRejected() {
            // arrange
            pointService.charge(USER_ID, 1_000L)

            // act
            assertThrows<Exception> { pointService.use(userId = USER_ID, amount = 1_001L, orderId = ORDER_ID) }

            // assert
            assertAll(
                { assertThat(pointRepository.findByUserId(USER_ID)?.balance).isEqualTo(1_000L) },
                { assertThat(ledgerSum()).isEqualTo(1_000L) },
                { assertThat(pointTransactionJpaRepository.count()).isEqualTo(1L) },
            )
        }
    }
}
