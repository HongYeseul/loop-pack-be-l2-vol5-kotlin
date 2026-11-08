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
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 실제 MySQL 에서 후보 조건과 결과 기록 확인, 발행은 가짜로 대체해 보낸 배치를 기록 */
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
    private val outboxResume: OutboxResume,
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

    @BeforeEach
    fun setUp() {
        publishSucceeds()
    }

    @AfterEach
    fun tearDown() {
        sentBatches.clear()
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("보낼 행을 고를 때,")
    @Nested
    inner class Picking {
        @DisplayName("배치 크기만큼 id 순으로 가져가고, 남은 행은 다음 회차에 가져간다.")
        @Test
        fun takesBatchSizeInOrder() {
            // arrange
            (1..5).forEach { append("$it", "e-$it") }

            // act
            val picked = List(3) { outboxRelay.runOnce() }

            // assert
            assertThat(picked).containsExactly(3, 2, 0)
            assertThat(sentBatches).containsExactly(listOf("e-1", "e-2", "e-3"), listOf("e-4", "e-5"))
        }

        @DisplayName("다음 시도 시각이 아직 오지 않은 행은 건너뛴다.")
        @Test
        fun skipsRowsNotDueYet() {
            // arrange
            val later = append("1", "e-later")
            append("2", "e-due")
            notDueUntilLater(later)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("e-due"))
        }
    }

    @DisplayName("같은 key 에 앞선 미완료 행이 있으면,")
    @Nested
    inner class WithEarlierUnfinishedRowOfSameKey {
        @DisplayName("뒤 행은 앞 행이 나간 다음 회차에 나간다.")
        @Test
        fun sendsLaterRowAfterEarlierRow() {
            // arrange
            append("1", "order-1-first")
            append("1", "order-1-second")

            // act
            outboxRelay.runOnce()
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("order-1-first"), listOf("order-1-second"))
        }

        @DisplayName("다른 key 의 행은 기다리지 않고 같은 회차에 나간다.")
        @Test
        fun doesNotBlockOtherKeys() {
            // arrange
            append("1", "order-1-first")
            append("1", "order-1-second")
            append("2", "order-2-first")

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("order-1-first", "order-2-first"))
        }

        @DisplayName("앞 행이 다음 시도를 기다리는 중이면, 뒤 행은 먼저 나가지 않는다.")
        @Test
        fun doesNotOvertakeRetryingRow() {
            // arrange
            val first = append("1", "order-1-first")
            append("1", "order-1-second")
            notDueUntilLater(first)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).isEmpty()
        }

        @DisplayName("앞 행이 FAILED 면, 뒤 행은 PENDING 그대로 멈춘다.")
        @Test
        fun stopsBehindFailedRow() {
            // arrange
            failed(append("1", "order-1-first"))
            val blocked = append("1", "order-1-second")

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).isEmpty()
            assertThat(row(blocked).status).isEqualTo(OutboxStatus.PENDING)
        }

        @DisplayName("앞 행이 FAILED 여도, 다른 key 의 행은 계속 나간다.")
        @Test
        fun failedRowDoesNotBlockOtherKeys() {
            // arrange
            failed(append("1", "order-1-first"))
            append("2", "order-2-first")

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("order-2-first"))
        }

        @DisplayName("앞 행을 다른 relay 가 선점 중이면, 뒤 행은 가져가지 않는다.")
        @Test
        fun waitsBehindRowClaimedByOtherRelay() {
            // arrange
            append("1", "order-1-first")
            append("1", "order-1-second")
            claimAs("relay-other", limit = 1)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).isEmpty()
        }
    }

    @DisplayName("보내는 데 성공하면,")
    @Nested
    inner class WhenPublished {
        @DisplayName("행을 PUBLISHED 로 바꾸고, 발행 시각과 보낸 relay 이름을 남긴다.")
        @Test
        fun recordsPublished() {
            // arrange
            val id = append("1", "e-1")

            // act
            outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED)
            assertThat(row.publishedAt?.toInstant()).isEqualTo(now)
            assertThat(row.claimedBy).isEqualTo(relayName.value)
        }

        @DisplayName("한 배치 안에서 성공한 행과 실패한 행을 각각 기록한다.")
        @Test
        fun recordsEachResultInBatch() {
            // arrange
            val ok = append("1", "e-ok")
            val ng = append("2", "e-ng")
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
        }
    }

    @DisplayName("보내지 못하면,")
    @Nested
    inner class WhenFailed {
        @DisplayName("일시적 실패는 PENDING 으로 두고, 시도 횟수를 올리고, 백오프만큼 다음 시도를 미룬다.")
        @Test
        fun schedulesRetry() {
            // arrange
            val id = append("1", "e-1")
            publishFails(TimeoutException("브로커 응답 없음"), retryable = true)

            // act
            outboxRelay.runOnce()

            // assert
            val row = row(id)
            assertThat(row.status).isEqualTo(OutboxStatus.PENDING)
            assertThat(row.attemptCount).isEqualTo(1)
            assertThat(row.nextAttemptAt.toInstant()).isEqualTo(now.plusSeconds(1))
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
            assertThat(row(id).status).isEqualTo(OutboxStatus.FAILED)
            assertThat(row(id).attemptCount).isEqualTo(3)
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
            assertThat(row(id).status).isEqualTo(OutboxStatus.FAILED)
            assertThat(row(id).attemptCount).isEqualTo(1)
        }

        @DisplayName("감싼 예외로 실패하면 last_error 에 안쪽 원인까지 남긴다.")
        @Test
        fun recordsWrappedCause() {
            // arrange
            val id = append("1", "e-1")
            publishFails(KafkaException("Send failed", TimeoutException("Topic order-events not present in metadata after 5000 ms.")), retryable = true)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(row(id).lastError)
                .isEqualTo("KafkaException: Send failed <- TimeoutException: Topic order-events not present in metadata after 5000 ms.")
        }

        @DisplayName("재시도를 예약하면 eventId 와 안쪽 원인을 경고 로그로 남긴다.")
        @Test
        fun logsRetryWithCause() {
            // arrange
            append("1", "e-1")
            publishFails(KafkaException("Send failed", TimeoutException("메타데이터 대기 초과")), retryable = true)

            // act
            val logs = capturingRelayLogs { outboxRelay.runOnce() }

            // assert
            assertThat(logs).anySatisfy {
                assertThat(it.level).isEqualTo(Level.WARN)
                assertThat(it.formattedMessage).contains("e-1", "재시도 예약", "TimeoutException: 메타데이터 대기 초과")
            }
        }
    }

    @DisplayName("다른 relay 와 함께 돌면,")
    @Nested
    inner class WithOtherRelay {
        @DisplayName("다른 relay 가 선점한 행은 가져가지 않는다.")
        @Test
        fun skipsRowClaimedByOtherRelay() {
            // arrange
            val claimed = append("1", "e-claimed")
            claimAs("relay-other", limit = 1)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).isEmpty()
            assertThat(row(claimed).claimedBy).isEqualTo("relay-other")
        }

        @DisplayName("lease 가 지난 CLAIMED 행은 다시 선점해 보낸다.")
        @Test
        fun reclaimsExpiredLease() {
            // arrange
            val id = append("1", "e-1")
            claimAs("relay-other", limit = 1)
            expireLease(id)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("e-1"))
            assertThat(row(id).claimedBy).isEqualTo(relayName.value)
        }

        @DisplayName("lease 가 지나 다시 선점하는 것은 실패가 아니므로 시도 횟수를 올리지 않는다.")
        @Test
        fun doesNotCountReclaimAsAttempt() {
            // arrange
            val id = append("1", "e-1")
            claimAs("relay-other", limit = 1)
            expireLease(id)

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(row(id).attemptCount).isZero()
        }

        @DisplayName("보내는 사이 lease 가 지나 다른 relay 가 먼저 PUBLISHED 를 기록했으면, 늦게 끝난 relay 의 완료 기록은 그 행을 바꾸지 않는다.")
        @Test
        fun lateSuccessDoesNotOverwrite() {
            // arrange
            val id = append("1", "e-1")
            val slowRelay = relay("relay-slow") { events ->
                expireLease(id)
                outboxRelay.runOnce()
                events.map { OutboxPublishResult.Published(it) }
            }

            // act
            slowRelay.runOnce()

            // assert
            assertThat(row(id).claimedBy).isEqualTo(relayName.value)
        }

        @DisplayName("늦게 끝난 relay 의 기록이 반영되지 않으면 경고 로그를 남긴다.")
        @Test
        fun warnsWhenRecordIsLost() {
            // arrange
            val id = append("1", "e-1")
            val slowRelay = relay("relay-slow") { events ->
                expireLease(id)
                outboxRelay.runOnce()
                events.map { OutboxPublishResult.Published(it) }
            }

            // act
            val logs = capturingRelayLogs { slowRelay.runOnce() }

            // assert
            assertThat(logs).anySatisfy {
                assertThat(it.level).isEqualTo(Level.WARN)
                assertThat(it.formattedMessage).contains("완료 기록 누락", "relay-slow")
            }
        }

        @DisplayName("다른 relay 가 다시 선점해 보내는 중이면, 늦게 끝난 relay 의 실패 기록은 그 행을 되돌리지 않는다.")
        @Test
        fun lateFailureDoesNotTouchOthersClaim() {
            // arrange
            val id = append("1", "e-1")
            val slowRelay = relay("relay-slow") { events ->
                expireLease(id)
                claimAs("relay-other", limit = 1)
                events.map { OutboxPublishResult.Failed(it, TimeoutException("늦은 실패"), retryable = true) }
            }

            // act
            slowRelay.runOnce()

            // assert
            assertThat(row(id).status).isEqualTo(OutboxStatus.CLAIMED)
            assertThat(row(id).claimedBy).isEqualTo("relay-other")
        }

        @DisplayName("후보를 고른 뒤 잠그기 전에 다른 relay 가 선점해 커밋하면, 그 행은 잠그지 않는다.")
        @Test
        fun doesNotLockRowClaimedAfterPicking() {
            // arrange
            append("1", "e-1")
            val current = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
            val picked = outboxEventJpaRepository.findClaimableIds(current, properties.candidateSize)
            claimAs("relay-other", limit = 1)

            // act
            val locked = TransactionTemplate(transactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            }.execute { outboxEventJpaRepository.lockClaimable(picked, current, properties.batchSize) }

            // assert
            assertThat(locked).isEmpty()
        }
    }

    @DisplayName("FAILED 행을 재개하면,")
    @Nested
    inner class WhenResumed {
        @DisplayName("시도 횟수를 비우고 바로 보낼 수 있는 PENDING 으로 되돌리며, 마지막 오류는 남긴다.")
        @Test
        fun resetsToPending() {
            // arrange
            val id = append("1", "e-1")
            failed(id)

            // act
            val resumed = outboxResume.resume(listOf("e-1"))

            // assert
            val row = row(id)
            assertThat(resumed).isEqualTo(mapOf("e-1" to true))
            assertThat(row.status).isEqualTo(OutboxStatus.PENDING)
            assertThat(row.attemptCount).isZero()
            assertThat(row.nextAttemptAt.toInstant()).isEqualTo(now)
            assertThat(row.lastError).isEqualTo("이전 실패")
        }

        @DisplayName("다음 회차에 그 행을 보낸다.")
        @Test
        fun sendsResumedRowNextRound() {
            // arrange
            failed(append("1", "e-1"))
            outboxResume.resume(listOf("e-1"))

            // act
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("e-1"))
        }

        @DisplayName("FAILED 뒤에 멈춰 있던 같은 key 의 행은, 재개한 행이 나간 다음 회차에 나간다.")
        @Test
        fun releasesRowsBehindInOrder() {
            // arrange
            failed(append("1", "order-1-first"))
            append("1", "order-1-second")
            outboxResume.resume(listOf("order-1-first"))

            // act
            outboxRelay.runOnce()
            outboxRelay.runOnce()

            // assert
            assertThat(sentBatches).containsExactly(listOf("order-1-first"), listOf("order-1-second"))
        }

        @DisplayName("FAILED 가 아닌 행이나 없는 eventId 는 건드리지 않는다.")
        @Test
        fun ignoresRowsThatAreNotFailed() {
            // arrange
            val pending = append("1", "e-pending")
            val published = append("2", "e-published")
            jdbcTemplate.update("UPDATE outbox SET status = 'PUBLISHED', attempt_count = 3 WHERE id = ?", published)

            // act
            val resumed = outboxResume.resume(listOf("e-pending", "e-published", "e-unknown"))

            // assert
            assertThat(resumed).isEqualTo(mapOf("e-pending" to false, "e-published" to false, "e-unknown" to false))
            assertThat(row(pending).status).isEqualTo(OutboxStatus.PENDING)
            assertThat(row(published).status).isEqualTo(OutboxStatus.PUBLISHED)
        }
    }

    // ---- 준비

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

    private fun notDueUntilLater(id: Long) {
        jdbcTemplate.update("UPDATE outbox SET next_attempt_at = ? WHERE id = ?", Timestamp.from(now.plusSeconds(10)), id)
    }

    private fun failed(id: Long) {
        jdbcTemplate.update("UPDATE outbox SET status = 'FAILED', attempt_count = 3, last_error = '이전 실패' WHERE id = ?", id)
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

    /** 이름이 다른 relay 하나 더, 보내는 동작을 테스트가 정함 */
    private fun relay(name: String, publish: (List<OutboxEvent>) -> List<OutboxPublishResult>): OutboxRelay {
        val publisher = object : OutboxPublisher {
            override fun publish(events: List<OutboxEvent>) = publish(events)
        }
        return OutboxRelay(outboxEventRepository, publisher, properties, clock, transactionManager, OutboxRelayName(name))
    }

    private fun capturingRelayLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(OutboxRelay::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClockConfig {
        @Primary
        @Bean
        fun fixedClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }
}
