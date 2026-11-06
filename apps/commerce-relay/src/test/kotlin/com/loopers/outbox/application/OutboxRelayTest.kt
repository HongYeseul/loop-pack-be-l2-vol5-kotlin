package com.loopers.outbox.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 실제 MySQL 에서 후보 조건과 결과 기록 확인, 발행은 가짜로 대체 */
@SpringBootTest(
    properties = [
        "outbox.relay.batch-size=3",
        "outbox.relay.max-attempts=3",
        "outbox.relay.backoff-initial=1s",
        "outbox.relay.backoff-max=4s",
    ],
)
@Import(OutboxRelayTest.FixedClockConfig::class)
@DisplayName("outbox relay 한 회차")
class OutboxRelayTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val properties: OutboxRelayProperties,
    private val relayName: OutboxRelayName,
    private val clock: Clock,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val now = Instant.parse("2026-11-05T01:00:00Z")
    }

    @MockkBean
    private lateinit var outboxPublisher: OutboxPublisher

    private val sentBatches = mutableListOf<List<String>>()
    private val relayLogger = LoggerFactory.getLogger(OutboxRelay::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        publishSucceeds()
        appender.list.clear()
        appender.start()
        relayLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        relayLogger.detachAppender(appender)
        appender.stop()
        sentBatches.clear()
        databaseCleanUp.truncateAllTables()
    }

    private fun publishSucceeds() {
        every { outboxPublisher.publish(any()) } answers {
            val events = firstArg<List<OutboxEvent>>()
            sentBatches += events.map { it.eventId }
            events.map { OutboxPublishResult.Published(it) }
        }
    }

    private fun publishFails(error: Throwable, retryable: Boolean) {
        every { outboxPublisher.publish(any()) } answers {
            val events = firstArg<List<OutboxEvent>>()
            sentBatches += events.map { it.eventId }
            events.map { OutboxPublishResult.Failed(it, error, retryable) }
        }
    }

    private fun append(aggregateId: String, eventId: String): Long = outboxEventJpaRepository.save(
        OutboxEvent(
            eventId = eventId,
            aggregateType = "ORDER",
            aggregateId = aggregateId,
            eventType = "OrderConfirmed",
            schemaVersion = 1,
            topic = "order-events",
            payload = """{"eventId":"$eventId"}""",
            now = ZonedDateTime.ofInstant(now, ZoneOffset.UTC),
        ),
    ).id

    private fun row(id: Long): OutboxEvent = outboxEventJpaRepository.findById(id).get()

    @DisplayName("발행이 성공하면,")
    @Nested
    inner class WhenPublished {
        @DisplayName("PENDING 행을 PUBLISHED 로 바꾸고 읽어 온 행 수를 돌려준다.")
        @Test
        fun marksPublished() {
            // arrange
            val id = append("1", "e-1")

            // act
            val count = outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertAll(
                { assertThat(count).isEqualTo(1) },
                { assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED) },
                { assertThat(row.publishedAt?.toInstant()).isEqualTo(now) },
                { assertThat(row.claimedBy).isEqualTo(relayName.value) },
                { assertThat(row.attemptCount).isZero() },
            )
        }

        @DisplayName("배치 크기만큼만 id 순으로 가져가고, 남은 행은 다음 회차에 나간다.")
        @Test
        fun takesBatchSizeInOrder() {
            // arrange
            (1..5).forEach { append("$it", "e-$it") }

            // act
            val first = outboxRelay.runOnce()
            val second = outboxRelay.runOnce()
            val third = outboxRelay.runOnce()

            // assert
            assertAll(
                { assertThat(listOf(first, second, third)).containsExactly(3, 2, 0) },
                { assertThat(sentBatches).containsExactly(listOf("e-1", "e-2", "e-3"), listOf("e-4", "e-5")) },
            )
        }

        @DisplayName("다음 시도 시각이 아직 오지 않은 행은 건너뛴다.")
        @Test
        fun skipsRowsNotDueYet() {
            // arrange
            val later = append("1", "e-1")
            append("2", "e-2")
            jdbcTemplate.update("UPDATE outbox SET next_attempt_at = ? WHERE id = ?", java.sql.Timestamp.from(now.plusSeconds(10)), later)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("e-2"))
            assertThat(row(later).status).isEqualTo(OutboxStatus.PENDING)
        }
    }

    @DisplayName("같은 key 에 앞선 미완료 행이 있으면,")
    @Nested
    inner class WhenEarlierRowIsUnfinished {
        @DisplayName("뒤 행은 후보가 아니고, 다른 key 의 행은 나간다. 앞 행이 끝나면 다음 회차에 나간다.")
        @Test
        fun waitsForEarlierRowOfSameKey() {
            // arrange
            append("1", "order-1-first")
            append("1", "order-1-second")
            append("2", "order-2-first")

            // act
            outboxRelay.runOnce()
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(
                listOf("order-1-first", "order-2-first"),
                listOf("order-1-second"),
            )
        }

        @DisplayName("앞 행이 다음 시도를 기다리는 중이어도 뒤 행은 먼저 나가지 않는다.")
        @Test
        fun doesNotOvertakeRetryingRow() {
            // arrange
            val first = append("1", "order-1-first")
            append("1", "order-1-second")
            jdbcTemplate.update("UPDATE outbox SET next_attempt_at = ? WHERE id = ?", java.sql.Timestamp.from(now.plusSeconds(10)), first)

            // act
            val count = outboxRelay.runOnce()

            // assert
            assertThat(count).isZero()
            assertThat(sentBatches).isEmpty()
        }

        @DisplayName("앞 행이 FAILED 면 같은 key 의 뒤 행은 PENDING 그대로 멈추고, 다른 key 는 계속 나간다.")
        @Test
        fun stopsOnlySameKeyBehindFailedRow() {
            // arrange
            val failed = append("1", "order-1-first")
            val blocked = append("1", "order-1-second")
            append("2", "order-2-first")
            jdbcTemplate.update("UPDATE outbox SET status = 'FAILED' WHERE id = ?", failed)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("order-2-first"))
            assertThat(row(blocked).status).isEqualTo(OutboxStatus.PENDING)
        }
    }

    @DisplayName("다른 relay 와 함께 돌면,")
    @Nested
    inner class WithOtherRelay {
        @DisplayName("다른 relay 가 선점한 행은 다시 가져가지 않고, 그 뒤의 같은 key 행도 기다리며, 다른 key 는 가져간다.")
        @Test
        fun skipsClaimedRowAndItsKey() {
            // arrange
            val claimed = append("1", "order-1-first")
            append("1", "order-1-second")
            append("2", "order-2-first")
            claimAs("relay-a", limit = 1)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("order-2-first"))
            assertThat(row(claimed).status).isEqualTo(OutboxStatus.CLAIMED)
            assertThat(row(claimed).claimedBy).isEqualTo("relay-a")
        }

        @DisplayName("lease 가 지난 CLAIMED 행은 다시 선점해 보내고, 시도 횟수는 올리지 않는다.")
        @Test
        fun reclaimsExpiredLease() {
            // arrange
            val id = append("1", "e-1")
            claimAs("relay-a", limit = 1)
            expireLease(id)

            // act
            outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertAll(
                { assertThat(sentBatches).containsExactly(listOf("e-1")) },
                { assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED) },
                { assertThat(row.claimedBy).isEqualTo(relayName.value) },
                { assertThat(row.attemptCount).isZero() },
            )
        }

        @DisplayName("보내는 사이 lease 가 지나 다른 relay 가 다시 보냈으면, 늦게 끝난 relay 의 완료 기록은 0건이고 경고를 남긴다.")
        @Test
        fun lateRelayRecordsNothing() {
            // arrange
            val id = append("1", "e-1")
            val slowPublisher = object : OutboxPublisher {
                override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
                    expireLease(id)
                    outboxRelay.runOnce()
                    sentBatches += events.map { it.eventId }
                    return events.map { OutboxPublishResult.Published(it) }
                }
            }
            val slowRelay = OutboxRelay(outboxEventRepository, slowPublisher, properties, clock, transactionManager, OutboxRelayName("relay-slow"))

            // act
            slowRelay.runOnce()

            // assert
            val row = row(id)
            assertAll(
                { assertThat(sentBatches).containsExactly(listOf("e-1"), listOf("e-1")) },
                { assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED) },
                { assertThat(row.claimedBy).isEqualTo(relayName.value) },
                {
                    assertThat(appender.list).anySatisfy {
                        assertThat(it.level).isEqualTo(Level.WARN)
                        assertThat(it.formattedMessage).contains("완료 기록 누락", "relay-slow")
                    }
                },
            )
        }

        @DisplayName("다른 relay 가 다시 선점해 보내는 중이면, 늦게 끝난 relay 의 실패 기록은 그 행을 되돌리지 않는다.")
        @Test
        fun lateFailureDoesNotTouchOthersClaim() {
            // arrange
            val id = append("1", "e-1")
            val slowPublisher = object : OutboxPublisher {
                override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
                    expireLease(id)
                    claimAs("relay-b", limit = 1)
                    return events.map { OutboxPublishResult.Failed(it, TimeoutException("늦은 실패"), retryable = true) }
                }
            }
            val slowRelay = OutboxRelay(outboxEventRepository, slowPublisher, properties, clock, transactionManager, OutboxRelayName("relay-slow"))

            // act
            slowRelay.runOnce()

            // assert
            val row = row(id)
            assertAll(
                { assertThat(row.status).isEqualTo(OutboxStatus.CLAIMED) },
                { assertThat(row.claimedBy).isEqualTo("relay-b") },
                { assertThat(row.attemptCount).isZero() },
                { assertThat(appender.list).anySatisfy { assertThat(it.formattedMessage).contains("결과 기록 누락", "relay-slow") } },
            )
        }

        @DisplayName("후보를 고른 뒤 잠그기 전에 다른 relay 가 선점해 커밋하면, 그 행은 잠그지 않는다.")
        @Test
        fun doesNotLockRowClaimedAfterPicking() {
            // arrange
            val id = append("1", "e-1")
            val current = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
            val picked = outboxEventJpaRepository.findClaimableIds(current, properties.candidateSize)
            claimAs("relay-b", limit = 1)

            // act
            val locked = TransactionTemplate(transactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            }.execute { outboxEventJpaRepository.lockClaimable(picked, current, properties.batchSize) }

            // assert
            assertThat(picked).containsExactly(id)
            assertThat(locked).isEmpty()
            assertThat(row(id).claimedBy).isEqualTo("relay-b")
        }

        private fun claimAs(relay: String, limit: Int) {
            val current = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
            TransactionTemplate(transactionManager).executeWithoutResult {
                outboxEventRepository.claim(relay, current, current.plus(properties.lease), limit, properties.candidateSize)
            }
        }

        private fun expireLease(id: Long) {
            jdbcTemplate.update("UPDATE outbox SET claimed_until = '2000-01-01 00:00:00' WHERE id = ?", id)
        }
    }

    @DisplayName("발행이 실패하면,")
    @Nested
    inner class WhenFailed {
        @DisplayName("일시적 실패는 PENDING 으로 두고 시도 횟수를 올리고 백오프만큼 다음 시도를 미룬다.")
        @Test
        fun schedulesRetry() {
            // arrange
            val id = append("1", "e-1")
            publishFails(TimeoutException("브로커 응답 없음"), retryable = true)

            // act
            outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertAll(
                { assertThat(row.status).isEqualTo(OutboxStatus.PENDING) },
                { assertThat(row.attemptCount).isEqualTo(1) },
                { assertThat(row.nextAttemptAt.toInstant()).isEqualTo(now.plusSeconds(1)) },
                { assertThat(row.lastError).contains("브로커 응답 없음") },
                { assertThat(row.publishedAt).isNull() },
            )
        }

        @DisplayName("감싼 예외로 실패하면 안쪽 원인까지 last_error 와 경고 로그에 남긴다.")
        @Test
        fun recordsWrappedCause() {
            // arrange
            val id = append("1", "e-1")
            publishFails(KafkaException("Send failed", TimeoutException("Topic order-events not present in metadata after 5000 ms.")), retryable = true)

            // act
            outboxRelay.runOnce()

            // assert
            val expected = "KafkaException: Send failed <- TimeoutException: Topic order-events not present in metadata after 5000 ms."
            assertThat(row(id).lastError).isEqualTo(expected)
            assertThat(appender.list).anySatisfy { assertThat(it.formattedMessage).contains(expected) }
        }

        @DisplayName("일시적 실패가 최대 시도 횟수에 이르면 FAILED 가 된다.")
        @Test
        fun failsWhenAttemptsExhausted() {
            // arrange
            val id = append("1", "e-1")
            jdbcTemplate.update("UPDATE outbox SET attempt_count = 2 WHERE id = ?", id)
            publishFails(TimeoutException("브로커 응답 없음"), retryable = true)

            // act
            outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertThat(row.status).isEqualTo(OutboxStatus.FAILED)
            assertThat(row.attemptCount).isEqualTo(3)
        }

        @DisplayName("다시 해도 안 되는 실패는 첫 시도에 FAILED 가 된다.")
        @Test
        fun failsImmediatelyWhenNotRetryable() {
            // arrange
            val id = append("1", "e-1")
            publishFails(RecordTooLargeException("메시지 크기 초과"), retryable = false)

            // act
            outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertThat(row.status).isEqualTo(OutboxStatus.FAILED)
            assertThat(row.attemptCount).isEqualTo(1)
            assertThat(row.lastError).contains("메시지 크기 초과")
        }

        @DisplayName("한 배치 안에서 성공한 행과 실패한 행을 각각 기록한다.")
        @Test
        fun recordsEachResultInBatch() {
            // arrange
            val ok = append("1", "e-1")
            val ng = append("2", "e-2")
            every { outboxPublisher.publish(any()) } answers {
                firstArg<List<OutboxEvent>>().map {
                    if (it.id == ok) OutboxPublishResult.Published(it) else OutboxPublishResult.Failed(it, TimeoutException("일부 실패"), true)
                }
            }

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(row(ok).status).isEqualTo(OutboxStatus.PUBLISHED)
            assertThat(row(ng).status).isEqualTo(OutboxStatus.PENDING)
            assertThat(row(ng).attemptCount).isEqualTo(1)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfig {
        @Primary
        @Bean
        fun fixedClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }
}
