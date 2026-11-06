package com.loopers.outbox.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true", "outbox.relay.backoff-initial=2s"])
@Import(OutboxRelayPublicationTest.PublicationConfig::class)
@Testcontainers
@DisplayName("outbox relay 의 Kafka 발행과 실패 처리")
class OutboxRelayPublicationTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val transactionManager: PlatformTransactionManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val topic = "order-events-${UUID.randomUUID()}"
        private val now = Instant.parse("2026-11-05T01:02:03.123456Z")

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

    private lateinit var consumer: KafkaConsumer<String, ByteArray>
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val relayLogger = LoggerFactory.getLogger(OutboxRelay::class.java) as Logger

    @BeforeEach
    fun setUp() {
        every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } answers { callOriginal() }
        consumer = KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ),
        )
        val partitions = listOf(TopicPartition(topic, 0), TopicPartition(topic, 1))
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        partitions.forEach { consumer.position(it, Duration.ofSeconds(5)) }
        appender = ListAppender<ILoggingEvent>().apply { start() }
        relayLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        consumer.close(Duration.ofSeconds(5))
        relayLogger.detachAppender(appender)
        appender.stop()
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("발행된 key·헤더·본문이 outbox 에 저장된 값과 바이트까지 같다")
    fun publishesStoredPayloadAsIs() {
        // 다시 직렬화하면 공백과 key 순서가 바뀌는 본문
        val payload = """{"orderId":1, "eventId":"order-confirmed:v1:1"}"""
        val id = save(eventId = "order-confirmed:v1:1", aggregateId = "1", payload = payload)

        assertThat(outboxRelay.runOnce()).isEqualTo(1)

        val record = consumer.poll(Duration.ofSeconds(15)).single()
        assertThat(record.topic()).isEqualTo(topic)
        assertThat(record.key()).isEqualTo("1")
        assertThat(record.header("eventId")).isEqualTo("order-confirmed:v1:1")
        assertThat(record.header("eventType")).isEqualTo("OrderConfirmed")
        assertThat(record.header("schemaVersion")).isEqualTo("1")
        assertThat(String(record.value(), Charsets.UTF_8)).isEqualTo(payload)
        val row = outboxEventJpaRepository.findById(id).get()
        assertThat(row.status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(row.publishedAt?.toInstant()).isEqualTo(now)
    }

    @Test
    @DisplayName("한 번 발행한 행은 다음 회차에 다시 보내지 않는다")
    fun publishesOnceAcrossRounds() {
        save(eventId = "order-confirmed:v1:1", aggregateId = "1")

        assertThat(outboxRelay.runOnce()).isEqualTo(1)
        assertThat(consumer.poll(Duration.ofSeconds(15))).hasSize(1)

        assertThat(outboxRelay.runOnce()).isZero()
        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
    }

    @Test
    @DisplayName("행을 쓴 트랜잭션이 커밋되기 전에는 다른 스레드의 relay 가 그 행을 보지 못한다")
    fun doesNotSeeUncommittedRow() {
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            save(eventId = "order-confirmed:v1:1", aggregateId = "1")

            assertThat(CompletableFuture.supplyAsync { outboxRelay.runOnce() }.get()).isZero()
            status.setRollbackOnly()
        }
        assertThat(consumer.poll(Duration.ofMillis(300))).isEmpty()
    }

    @Test
    @DisplayName("send 가 바로 실패하면 행은 재시도 대기로 남는다")
    fun schedulesRetryWhenSendThrows() {
        every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } throws KafkaException("전송 실패 주입")
        val id = save(eventId = "order-confirmed:v1:1", aggregateId = "1")

        outboxRelay.runOnce()

        assertRetryScheduled(id)
    }

    @Test
    @DisplayName("전송 결과가 나중에 실패해도 행은 재시도 대기로 남는다")
    fun schedulesRetryWhenSendResultFails() {
        every { outboxKafkaTemplate.send(any<ProducerRecord<String, ByteArray>>()) } returns
            CompletableFuture.failedFuture(KafkaException("비동기 전송 실패 주입"))
        val id = save(eventId = "order-confirmed:v1:1", aggregateId = "1")

        outboxRelay.runOnce()

        assertRetryScheduled(id)
    }

    private fun assertRetryScheduled(id: Long) {
        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
        val row = outboxEventJpaRepository.findById(id).get()
        assertThat(row.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(row.attemptCount).isEqualTo(1)
        assertThat(row.nextAttemptAt.toInstant()).isEqualTo(now.plusSeconds(2))
        assertThat(row.lastError).contains("전송 실패 주입")
        assertThat(appender.list).anySatisfy {
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.formattedMessage).contains(row.eventId, "재시도 예약")
        }
    }

    private fun save(eventId: String, aggregateId: String, payload: String = """{"eventId":"$eventId"}"""): Long =
        outboxEventJpaRepository.save(
            OutboxEvent(
                eventId = eventId,
                aggregateType = "ORDER",
                aggregateId = aggregateId,
                eventType = "OrderConfirmed",
                schemaVersion = 1,
                topic = topic,
                payload = payload,
                now = ZonedDateTime.ofInstant(now, ZoneOffset.UTC),
            ),
        ).id

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.let { String(it, Charsets.UTF_8) }

    @TestConfiguration(proxyBeanMethods = false)
    class PublicationConfig {
        @Bean
        fun publicationTopic(): NewTopic = NewTopic(topic, 2, 1)

        @Primary
        @Bean
        fun publicationClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }
}
