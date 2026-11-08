package com.loopers.job.outbox

import com.loopers.batch.job.outbox.BackfillOrderConfirmedOutboxJobConfig
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

/** order_item, outbox 는 batch 가 매핑하지 않아 테스트가 직접 만듦. 실제 API 스키마와의 호환은 commerce-api 재현 테스트가 확인 */
@SpringBootTest
@SpringBatchTest
@TestPropertySource(properties = ["spring.batch.job.name=${BackfillOrderConfirmedOutboxJobConfig.JOB_NAME}"])
@DisplayName("outbox 도입 전 확정 주문을 outbox 에 채우는 job")
class BackfillOrderConfirmedOutboxJobE2ETest @Autowired constructor(
    private val jobLauncherTestUtils: JobLauncherTestUtils,
    @param:Qualifier(BackfillOrderConfirmedOutboxJobConfig.JOB_NAME) private val job: Job,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @BeforeEach
    fun setUp() {
        val hasConfirmedAt = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'confirmed_at'",
            Int::class.java,
        )
        if (hasConfirmedAt == 0) jdbcTemplate.execute("ALTER TABLE orders ADD COLUMN confirmed_at DATETIME(6) NULL")
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS order_item (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, product_id BIGINT NOT NULL, quantity INT NOT NULL
            )
            """,
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS outbox (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
              event_id VARCHAR(200) NOT NULL, aggregate_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(100) NOT NULL,
              event_type VARCHAR(100) NOT NULL, schema_version INT NOT NULL, topic VARCHAR(100) NOT NULL, payload TEXT NOT NULL,
              status VARCHAR(20) NOT NULL, attempt_count INT NOT NULL, next_attempt_at DATETIME(6) NOT NULL,
              last_error VARCHAR(1000), claimed_by VARCHAR(100), claimed_until DATETIME(6), published_at DATETIME(6),
              CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
            )
            """,
        )
        jobLauncherTestUtils.job = job
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE order_item")
        jdbcTemplate.execute("TRUNCATE TABLE outbox")
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("outbox 가 없는 확정 주문이면,")
    @Nested
    inner class WhenOutboxIsMissing {
        @DisplayName("commerce-api 가 확정할 때 저장하는 본문과 같은 본문으로 행을 만든다 (필드 순서, 시각 형식, 상품 순 정렬까지).")
        @Test
        fun writesSamePayloadAsApi() {
            // arrange
            val orderId = confirmedOrder("2026-11-05 11:07:08.498649", 9002L to 1, 9001L to 2)

            // act
            launch(orderId)

            // assert
            assertThat(payloadOf(orderId)).isEqualTo(apiPayload(orderId))
        }

        @DisplayName("행의 key, 종류, topic 은 확정 때와 같고, PENDING 이면서 바로 보낼 수 있는 시각으로 들어간다.")
        @Test
        fun writesPendingRowDueNow() {
            // arrange
            val orderId = confirmedOrder("2026-11-05 11:07:08.498649", 9001L to 2)

            // act
            launch(orderId)

            // assert
            val row = jdbcTemplate.queryForMap("SELECT * FROM outbox WHERE event_id = ?", "order-confirmed:v1:$orderId")
            assertAll(
                { assertThat(row["aggregate_type"]).isEqualTo("ORDER") },
                { assertThat(row["aggregate_id"]).isEqualTo(orderId.toString()) },
                { assertThat(row["event_type"]).isEqualTo("OrderConfirmed") },
                { assertThat(row["schema_version"]).isEqualTo(1) },
                { assertThat(row["topic"]).isEqualTo("order-events") },
                { assertThat(row["status"]).isEqualTo("PENDING") },
                { assertThat(row["attempt_count"]).isEqualTo(0) },
                { assertThat(isDueNowInUtc(orderId)).isTrue() },
            )
        }

        @DisplayName("주문은 읽기만 하고 상태와 확정 시각을 바꾸지 않는다.")
        @Test
        fun doesNotChangeOrder() {
            // arrange
            val orderId = confirmedOrder("2026-11-05 11:07:08.498649", 9001L to 2)
            val before = orderRow(orderId)

            // act
            launch(orderId)

            // assert
            assertThat(orderRow(orderId)).isEqualTo(before)
        }
    }

    @DisplayName("같은 eventId 의 outbox 행이 이미 있으면,")
    @Nested
    inner class WhenOutboxExists {
        @DisplayName("본문이 같으면 새로 만들지 않고 성공으로 끝난다.")
        @Test
        fun skipsSamePayload() {
            // arrange
            val orderId = confirmedOrder("2026-11-05 11:07:08.498649", 9001L to 2, 9002L to 1)
            insertOutbox(orderId, apiPayload(orderId))

            // act
            val outcome = launch(orderId)

            // assert
            assertThat(outcome).isEqualTo(Outcome(ExitStatus.COMPLETED.exitCode, writeCount = 0))
            assertThat(outboxCount()).isEqualTo(1)
        }

        @DisplayName("본문이 다르면 실패로 끝나고 기존 행은 건드리지 않는다.")
        @Test
        fun failsOnDifferentPayload() {
            // arrange
            val orderId = confirmedOrder("2026-11-05 11:07:08.498649", 9001L to 2)
            val stored = apiPayload(orderId).replace("\"quantity\":2", "\"quantity\":3")
            insertOutbox(orderId, stored)

            // act
            val outcome = launch(orderId)

            // assert
            assertThat(outcome.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
            assertThat(payloadOf(orderId)).isEqualTo(stored)
        }
    }

    @DisplayName("같은 대상으로 두 번 돌려도 outbox 행은 주문마다 하나다.")
    @Test
    fun isRepeatable() {
        // arrange
        val orderId = confirmedOrder("2026-11-05 11:07:08.498649", 9001L to 2)

        // act
        launch(orderId)
        launch(orderId)

        // assert
        assertThat(outboxCount()).isEqualTo(1)
    }

    @DisplayName("한 주문이 실패해도, 함께 지정한 다른 주문은 채워진다.")
    @Test
    fun continuesOtherOrdersAfterFailure() {
        // arrange
        val conflicting = confirmedOrder("2026-11-05 11:07:08.498649", 9001L to 2)
        insertOutbox(conflicting, "{}")
        val other = confirmedOrder("2026-11-05 11:08:00.000001", 9001L to 1)

        // act
        launch(conflicting, other)

        // assert
        assertThat(payloadOf(other)).isNotNull()
    }

    @DisplayName("원본이 부족하면 추측해서 만들지 않는다,")
    @Nested
    inner class WhenSourceIsIncomplete {
        @DisplayName("확정되지 않은 주문이면 실패로 끝나고 행을 만들지 않는다.")
        @Test
        fun refusesNotConfirmedOrder() {
            // arrange
            val draft = order("DRAFT", confirmedAt = null)

            // act
            val outcome = launch(draft)

            // assert
            assertThat(outcome.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
            assertThat(outboxCount()).isZero()
        }

        @DisplayName("품목이 없는 확정 주문이면 실패로 끝나고 행을 만들지 않는다.")
        @Test
        fun refusesOrderWithoutItems() {
            // arrange
            val noItems = order("CONFIRMED", confirmedAt = "2026-11-05 11:07:08.498649")

            // act
            val outcome = launch(noItems)

            // assert
            assertThat(outcome.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
            assertThat(outboxCount()).isZero()
        }
    }

    private fun orderRow(orderId: Long) = jdbcTemplate.queryForMap("SELECT status, confirmed_at, updated_at FROM orders WHERE id = ?", orderId)

    // JobExecution 을 돌려주면 @SpringBatchTest 가 이 메서드를 실행 컨텍스트 생성용으로 오인해 호출
    private fun launch(vararg orderIds: Long): Outcome {
        val execution = jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addString("orderIds", orderIds.joinToString(";"))
                .addLong("run", System.nanoTime())
                .toJobParameters(),
        )
        return Outcome(execution.exitStatus.exitCode, execution.stepExecutions.single().writeCount)
    }

    data class Outcome(val exitCode: String, val writeCount: Long)

    private fun confirmedOrder(confirmedAt: String, vararg items: Pair<Long, Int>): Long {
        val orderId = order("CONFIRMED", confirmedAt)
        items.forEach { (productId, quantity) ->
            jdbcTemplate.update("INSERT INTO order_item (order_id, product_id, quantity) VALUES (?, ?, ?)", orderId, productId, quantity)
        }
        return orderId
    }

    private fun order(status: String, confirmedAt: String?): Long {
        jdbcTemplate.update(
            """
            INSERT INTO orders (created_at, updated_at, status, expires_at, confirmed_at)
            VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
            """,
            status,
            confirmedAt,
        )
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    private fun insertOutbox(orderId: Long, payload: String) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox (created_at, updated_at, event_id, aggregate_type, aggregate_id, event_type, schema_version,
              topic, payload, status, attempt_count, next_attempt_at)
            VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, 'ORDER', ?, 'OrderConfirmed', 1, 'order-events', ?, 'PUBLISHED', 0, UTC_TIMESTAMP(6))
            """,
            "order-confirmed:v1:$orderId",
            orderId.toString(),
            payload,
        )
    }

    // commerce-api 가 확정할 때 실제로 저장한 본문 형식 (로컬 outbox 행에서 확인)
    private fun apiPayload(orderId: Long): String =
        """{"orderId":$orderId,"confirmedAt":"2026-11-05T11:07:08.498649Z","items":[{"productId":9001,"quantity":2},""" +
            """{"productId":9002,"quantity":1}],"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:$orderId"}"""

    private fun isDueNowInUtc(orderId: Long): Boolean = jdbcTemplate.queryForObject(
        "SELECT next_attempt_at <= UTC_TIMESTAMP(6) AND next_attempt_at > UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE FROM outbox WHERE aggregate_id = ?",
        Boolean::class.java,
        orderId.toString(),
    )!!

    private fun outboxCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Int::class.java)!!

    private fun payloadOf(orderId: Long): String? =
        jdbcTemplate.queryForList("SELECT payload FROM outbox WHERE event_id = ?", String::class.java, "order-confirmed:v1:$orderId").singleOrNull()
}
