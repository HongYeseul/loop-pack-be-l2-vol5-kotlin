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
import com.loopers.utils.TopicReader
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** 실제 MySQL·Kafka 에서 outbox 행이 브로커까지 나가는 경로와 전송 실패 기록 */
@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true", "outbox.relay.backoff-initial=2s"])
@Import(OutboxRelayPublicationTest.PublicationConfig::class)
@Testcontainers
@DisplayName("outbox relay 의 Kafka 발행")
class OutboxRelayPublicationTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val kafkaOutboxPublisher: OutboxPublisher,
    private val properties: OutboxRelayProperties,
    private val transactionManager: PlatformTransactionManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val topic = "order-events-${UUID.randomUUID()}"
        private val now = Instant.parse("2026-11-05T01:02:03.123456Z")
        private const val EVENT_ID = "order-confirmed:v1:1"

        // 다시 직렬화하면 공백과 key 순서가 바뀌는 본문
        private const val PAYLOAD = """{"orderId":1, "eventId":"order-confirmed:v1:1"}"""

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    @SpykBean(name = "outboxKafkaTemplate")
    private lateinit var outboxKafkaTemplate: KafkaTemplate<String, ByteArray>

    private lateinit var broker: TopicReader

    @BeforeEach
    fun setUp() {
        every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } answers { callOriginal() }
        broker = TopicReader(kafka.bootstrapServers, topic)
    }

    @AfterEach
    fun tearDown() {
        broker.close()
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("브로커로 보내면,")
    @Nested
    inner class WhenPublished {
        @Test
        @DisplayName("key 는 aggregateId 이고, 본문은 저장된 문자열을 바이트까지 그대로 보낸다")
        fun sendsStoredPayloadAsIs() {
            save()

            outboxRelay.runOnce()

            val record = broker.read(count = 1).single()
            assertThat(record.key).isEqualTo("1")
            assertThat(record.value).isEqualTo(PAYLOAD)
        }

        @Test
        @DisplayName("헤더에 eventId, eventType, schemaVersion 을 싣는다")
        fun putsEventHeaders() {
            save()

            outboxRelay.runOnce()

            assertThat(broker.read(count = 1).single().headers)
                .containsEntry("eventId", EVENT_ID)
                .containsEntry("eventType", "OrderConfirmed")
                .containsEntry("schemaVersion", "1")
        }

        @Test
        @DisplayName("브로커가 받았다고 확인하면 행을 PUBLISHED 로 바꾸고 발행 시각을 남긴다")
        fun recordsPublished() {
            val id = save()

            outboxRelay.runOnce()

            val row = row(id)
            assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED)
            assertThat(row.publishedAt?.toInstant()).isEqualTo(now)
        }

        @Test
        @DisplayName("한 번 보낸 행은 다음 회차에 다시 보내지 않는다")
        fun doesNotSendAgain() {
            save()
            outboxRelay.runOnce()
            broker.read(count = 1)

            outboxRelay.runOnce()

            assertThat(broker.readRemaining()).isEmpty()
        }
    }

    @Test
    @DisplayName("행을 쓴 트랜잭션이 커밋되기 전에는 다른 스레드의 relay 가 그 행을 보지 못한다")
    fun doesNotSeeUncommittedRow() {
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            save()

            val picked = CompletableFuture.supplyAsync { outboxRelay.runOnce() }.get()

            assertThat(picked).isZero()
            status.setRollbackOnly()
        }
    }

    @DisplayName("브로커가 받은 뒤 결과를 기록하기 전에 멈췄다가 relay 를 새로 띄우면,")
    @Nested
    inner class WhenStoppedAfterBrokerAck {
        private var id = 0L
        private lateinit var afterStop: OutboxEvent
        private lateinit var records: List<TopicReader.Record>

        @BeforeEach
        fun stopThenRestart() {
            id = save()

            assertThrows<StoppedBeforeRecord> { relay("relay-stopped", stopAfterBrokerAck(), now).runOnce() }
            afterStop = row(id)

            // 재시작: relay 를 새로 만들고 시계만 lease 뒤로 (DB 상태는 건드리지 않음)
            relay("relay-restarted", kafkaOutboxPublisher, now.plus(properties.lease).plusSeconds(1)).runOnce()
            records = broker.read(count = 2)
        }

        @Test
        @DisplayName("멈춘 직후 행은 발행 시각 없이 CLAIMED 로 남는다")
        fun leavesClaimedRow() {
            assertThat(afterStop.status).isEqualTo(OutboxStatus.CLAIMED)
            assertThat(afterStop.publishedAt).isNull()
        }

        @Test
        @DisplayName("새 relay 가 같은 사건을 다시 보내, 브로커에 같은 eventId 와 본문이 서로 다른 offset 으로 두 번 남는다")
        fun brokerHasSameEventTwice() {
            assertThat(records.map { it.eventId to it.value }).containsExactly(EVENT_ID to PAYLOAD, EVENT_ID to PAYLOAD)
            assertThat(records.map { it.offset }.distinct()).hasSize(2)
        }

        @Test
        @DisplayName("다시 보낸 뒤 행은 새 relay 이름으로 PUBLISHED 가 되고, 실패가 아니므로 시도 횟수는 그대로다")
        fun endsPublishedByRestartedRelay() {
            val row = row(id)
            assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED)
            assertThat(row.claimedBy).isEqualTo("relay-restarted")
            assertThat(row.attemptCount).isZero()
        }
    }

    @DisplayName("보내지 못하면,")
    @Nested
    inner class WhenSendFails {
        @Test
        @DisplayName("send 가 바로 실패하면 행은 PENDING 으로 남고, 시도 횟수를 올리고, 백오프만큼 다음 시도를 미룬다")
        fun schedulesRetryWhenSendThrows() {
            every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } throws KafkaException("전송 실패 주입")
            val id = save()

            outboxRelay.runOnce()

            assertRetryScheduled(row(id))
        }

        @Test
        @DisplayName("전송 결과가 나중에 실패해도 행은 PENDING 으로 남고, 시도 횟수를 올리고, 백오프만큼 다음 시도를 미룬다")
        fun schedulesRetryWhenSendResultFails() {
            every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } returns
                CompletableFuture.failedFuture(KafkaException("비동기 전송 실패 주입"))
            val id = save()

            outboxRelay.runOnce()

            assertRetryScheduled(row(id))
        }

        @Test
        @DisplayName("재시도를 예약하면 eventId 와 함께 경고 로그를 남긴다")
        fun logsRetry() {
            every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } throws KafkaException("전송 실패 주입")
            save()

            val logs = capturingRelayLogs { outboxRelay.runOnce() }

            assertThat(logs).anySatisfy {
                assertThat(it.level).isEqualTo(Level.WARN)
                assertThat(it.formattedMessage).contains(EVENT_ID, "재시도 예약")
            }
        }

        private fun assertRetryScheduled(row: OutboxEvent) {
            assertThat(row.status).isEqualTo(OutboxStatus.PENDING)
            assertThat(row.attemptCount).isEqualTo(1)
            assertThat(row.nextAttemptAt.toInstant()).isEqualTo(now.plusSeconds(2))
            assertThat(row.lastError).contains("전송 실패 주입")
        }
    }

    class StoppedBeforeRecord : RuntimeException("브로커 확인 뒤 결과 기록 전 중단")

    private fun stopAfterBrokerAck() = object : OutboxPublisher {
        override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
            check(kafkaOutboxPublisher.publish(events).all { it is OutboxPublishResult.Published })
            throw StoppedBeforeRecord()
        }
    }

    private fun relay(name: String, publisher: OutboxPublisher, at: Instant) = OutboxRelay(
        outboxEventRepository,
        publisher,
        properties,
        Clock.fixed(at, ZoneOffset.UTC),
        transactionManager,
        OutboxRelayName(name),
    )

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

    private fun save(): Long = outboxEventJpaRepository.save(
        OutboxEvent(
            eventId = EVENT_ID,
            aggregateType = "ORDER",
            aggregateId = "1",
            eventType = "OrderConfirmed",
            schemaVersion = 1,
            topic = topic,
            payload = PAYLOAD,
            now = ZonedDateTime.ofInstant(now, ZoneOffset.UTC),
        ),
    ).id

    private fun row(id: Long): OutboxEvent = outboxEventJpaRepository.findById(id).get()

    @TestConfiguration(proxyBeanMethods = false)
    class PublicationConfig {
        @Bean
        fun publicationTopic(): NewTopic = NewTopic(topic, 2, 1)

        @Primary
        @Bean
        fun publicationClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }
}
