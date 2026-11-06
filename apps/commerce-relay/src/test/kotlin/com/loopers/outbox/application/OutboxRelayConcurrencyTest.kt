package com.loopers.outbox.application

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true"])
@Import(OutboxRelayConcurrencyTest.TopicConfig::class)
@Testcontainers
@DisplayName("relay 두 대가 함께 돌 때의 선점")
class OutboxRelayConcurrencyTest @Autowired constructor(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val outboxPublisher: OutboxPublisher,
    private val properties: OutboxRelayProperties,
    private val clock: Clock,
    private val transactionManager: PlatformTransactionManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val topic = "order-events-${UUID.randomUUID()}"

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    private val executor = Executors.newFixedThreadPool(2)
    private lateinit var consumer: KafkaConsumer<String, ByteArray>

    @BeforeEach
    fun setUp() {
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
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        consumer.close(Duration.ofSeconds(5))
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("두 대가 동시에 선점하면 나눠 가져가고, 행마다 한 번씩만 나간다")
    fun splitsRowsBetweenRelays() {
        val eventIds = (1L..10L).map { save(it) }
        val bothClaimed = CyclicBarrier(2)
        val relays = listOf("relay-a", "relay-b").map { relay(it, waitingAt = bothClaimed) }

        val counts = relays.map { CompletableFuture.supplyAsync({ it.runOnce() }, executor) }.map { it.get(30, SECONDS) }

        assertThat(counts).containsExactly(5, 5)
        assertThat(receive(expected = 10)).containsExactlyInAnyOrderElementsOf(eventIds)
        val rows = outboxEventJpaRepository.findAll()
        assertThat(rows).allSatisfy { assertThat(it.status).isEqualTo(OutboxStatus.PUBLISHED) }
        assertThat(rows.groupingBy { it.claimedBy }.eachCount()).isEqualTo(mapOf("relay-a" to 5, "relay-b" to 5))
    }

    @Test
    @DisplayName("다른 relay 가 선점하느라 잠근 행은 기다리지 않고 건너뛰어 나머지를 가져간다")
    fun skipsRowsLockedByOtherRelay() {
        val eventIds = (1L..10L).map { save(it) }
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = CompletableFuture.runAsync(
            {
                TransactionTemplate(transactionManager).apply {
                    isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                }.executeWithoutResult {
                    val now = ZonedDateTime.now(clock)
                    outboxEventRepository.claim("relay-a", now, now.plus(properties.lease), 5, properties.candidateSize)
                    locked.countDown()
                    release.await(30, SECONDS)
                }
            },
            executor,
        )
        assertThat(locked.await(10, SECONDS)).isTrue()

        val count = CompletableFuture.supplyAsync({ relay("relay-b").runOnce() }, executor).get(2, SECONDS)
        release.countDown()
        holder.get(10, SECONDS)

        assertThat(count).isEqualTo(5)
        assertThat(receive(expected = 5)).containsExactlyInAnyOrderElementsOf(eventIds.drop(5))
    }

    private fun relay(name: String, waitingAt: CyclicBarrier? = null): OutboxRelay {
        val publisher = object : OutboxPublisher {
            override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
                waitingAt?.await(10, SECONDS)
                return outboxPublisher.publish(events)
            }
        }
        return OutboxRelay(
            outboxEventRepository,
            publisher,
            properties.copy(batchSize = 5),
            clock,
            transactionManager,
            OutboxRelayName(name),
        )
    }

    private fun save(orderId: Long): String = outboxEventJpaRepository.save(
        OutboxEvent(
            eventId = "order-confirmed:v1:$orderId",
            aggregateType = "ORDER",
            aggregateId = orderId.toString(),
            eventType = "OrderConfirmed",
            schemaVersion = 1,
            topic = topic,
            payload = """{"orderId":$orderId}""",
            now = ZonedDateTime.now(clock),
        ),
    ).eventId

    private fun receive(expected: Int): List<String> {
        val eventIds = mutableListOf<String>()
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (eventIds.size < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach { record ->
                eventIds += String(record.headers().lastHeader("eventId").value(), Charsets.UTF_8)
            }
        }
        consumer.poll(Duration.ofMillis(500)).forEach { record ->
            eventIds += String(record.headers().lastHeader("eventId").value(), Charsets.UTF_8)
        }
        return eventIds
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TopicConfig {
        @Bean
        fun concurrencyTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
