package com.loopers.outbox.experiment

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxPublisher
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
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
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true"])
@Import(RelayDuplicationTest.TopicConfig::class)
@Testcontainers
@DisplayName("relay 를 여러 대 띄울 때의 중복과 잠금 재현")
class RelayDuplicationTest @Autowired constructor(
    entityManager: EntityManager,
    outboxPublisher: OutboxPublisher,
    transactionManager: PlatformTransactionManager,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    environment: Environment,
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

    private val relays = ExperimentRelays(entityManager, outboxPublisher, transactionManager)
    private val observer = LockObserver(environment)
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
    @DisplayName("장치 없이 두 대가 같은 행을 함께 읽으면 둘 다 보내 같은 eventId 가 두 번 쌓이고, 늦게 끝난 쪽의 완료 기록은 0건이다")
    fun duplicatesWithoutClaim() {
        val eventIds = (1L..3L).map { save(orderId = it) }
        val bothRead = CyclicBarrier(2)

        val rounds = List(2) {
            CompletableFuture.supplyAsync({ relays.withoutClaim(10) { bothRead.await(10, SECONDS) } }, executor)
        }.map { it.get(30, SECONDS) }

        assertThat(rounds).allSatisfy { assertThat(it.read).containsExactlyElementsOf(eventIds) }
        assertThat(rounds.map { it.marked }).containsExactlyInAnyOrder(3, 0)
        assertThat(receive(expected = 6).groupingBy { it }.eachCount()).isEqualTo(eventIds.associateWith { 2 })
        assertThat(outboxEventJpaRepository.findAll()).allSatisfy { assertThat(it.status).isEqualTo(OutboxStatus.PUBLISHED) }
    }

    @Test
    @DisplayName("잠금을 잡은 채 보내면 다른 relay 는 잠긴 행을 건너뛰어 중복이 없지만, 전송이 끝날 때까지 트랜잭션과 행 잠금이 남는다")
    fun holdsLockWhileSending() {
        val eventIds = (1L..3L).map { save(orderId = it) }
        val ids = outboxEventJpaRepository.findAll().map { it.id.toString() }
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)

        val first = CompletableFuture.supplyAsync(
            {
                relays.holdingLock(10, whileHolding = {
                    holding.countDown()
                    release.await(30, SECONDS)
                })
            },
            executor,
        )
        assertThat(holding.await(10, SECONDS)).isTrue()
        val second = relays.holdingLock(10)
        val lockedIds = observer.lockedPrimaryKeys()
        val lockingTransactions = observer.transactionsHoldingLocks()
        release.countDown()
        val firstRound = first.get(30, SECONDS)

        assertThat(second.read).isEmpty()
        assertThat(lockedIds).containsAll(ids)
        assertThat(lockingTransactions).isEqualTo(1)
        assertThat(firstRound.read).containsExactlyElementsOf(eventIds)
        assertThat(firstRound.marked).isEqualTo(3)
        assertThat(receive(expected = 3)).containsExactlyInAnyOrderElementsOf(eventIds)
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
            now = ZonedDateTime.now(),
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
        fun duplicationTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
