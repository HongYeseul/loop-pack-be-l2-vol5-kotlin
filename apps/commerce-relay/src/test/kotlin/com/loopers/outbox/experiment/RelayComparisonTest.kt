package com.loopers.outbox.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.outbox.application.OutboxRelay
import com.loopers.outbox.application.OutboxRelayName
import com.loopers.outbox.application.OutboxRelayProperties
import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxPublishResult
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/** 같은 데이터에서 relay 방식만 바꿔 숫자를 남김, 결과는 build/reports/week7/relay-comparison.json */
@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true"])
@Import(RelayComparisonTest.Topics::class)
@Testcontainers
@DisplayName("relay 방식별 비교 기록")
class RelayComparisonTest @Autowired constructor(
    private val entityManager: EntityManager,
    private val outboxPublisher: OutboxPublisher,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val properties: OutboxRelayProperties,
    private val clock: Clock,
    private val transactionManager: PlatformTransactionManager,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    environment: Environment,
) {
    companion object {
        private const val ORDERS = 50
        private const val ROWS_PER_ORDER = 2
        private const val CONFIRMS = 20
        private const val BATCH = 10
        private val SEND_DELAY: Duration = Duration.ofMillis(50)
        private val run = UUID.randomUUID().toString().take(8)
        private val topics = listOf("single", "no-claim", "lock", "lock-rc", "claim").associateWith { "relay-$it-$run" }

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    private val observer = LockObserver(environment)
    private val executor = Executors.newFixedThreadPool(3)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("4단계 한 대, 장치 없이 두 대, 잠금 잡은 채 두 대, 선점과 lease 두 대를 같은 데이터로 돌려 비교한다")
    fun recordsComparison() {
        val experiment = ExperimentRelays(entityManager, delayed(outboxPublisher), transactionManager)
        val results = listOf(
            scenario("4단계: 장치 없이 한 대", topics.getValue("single"), relays = 1) { { experiment.withoutClaim(BATCH).toStat() } },
            scenario("장치 없이 두 대", topics.getValue("no-claim"), relays = 2) { { experiment.withoutClaim(BATCH).toStat() } },
            scenario("잠금을 잡은 채 전송, 두 대", topics.getValue("lock"), relays = 2) { { experiment.holdingLock(BATCH).toStat() } },
            scenario("잠금을 잡은 채 전송, 두 대, READ COMMITTED", topics.getValue("lock-rc"), relays = 2) {
                { experiment.holdingLock(BATCH, TransactionDefinition.ISOLATION_READ_COMMITTED).toStat() }
            },
            scenario("선점과 lease, 두 대", topics.getValue("claim"), relays = 2) { index ->
                val relay = OutboxRelay(
                    outboxEventRepository,
                    delayed(outboxPublisher),
                    properties.copy(batchSize = BATCH),
                    clock,
                    transactionManager,
                    OutboxRelayName("relay-$index"),
                )
                val round: () -> Stat = { Stat(read = relay.runOnce(), marked = null, deadlocks = 0) }
                round
            },
        )
        saveEvidence(results)

        val total = ORDERS * ROWS_PER_ORDER + CONFIRMS
        assertThat(results).allSatisfy {
            assertThat(it.distinctEventIds).isEqualTo(total)
            assertThat(it.unpublishedRows).isZero()
        }
        assertThat(results.single { it.name == "4단계: 장치 없이 한 대" }.duplicates).isZero()
        assertThat(results.single { it.name == "장치 없이 두 대" }.duplicates).isPositive()
        assertThat(results.single { it.name == "선점과 lease, 두 대" }).satisfies({
            assertThat(it.duplicates).isZero()
            assertThat(it.deadlocks).isZero()
        })
    }

    private fun scenario(name: String, topic: String, relays: Int, roundOf: (Int) -> () -> Stat): Result {
        databaseCleanUp.truncateAllTables()
        (1..ORDERS).forEach { order -> repeat(ROWS_PER_ORDER) { save(topic, order.toLong(), it) } }
        val lockWaitsBefore = observer.rowLockWaits()
        val lockTimeBefore = observer.rowLockTimeMillis()
        val start = CyclicBarrier(relays + 1)
        val confirmMillis = ConcurrentLinkedQueue<Long>()
        val startedAt = System.nanoTime()

        val confirms = CompletableFuture.runAsync(
            {
                start.await(10, SECONDS)
                repeat(CONFIRMS) {
                    val begin = System.nanoTime()
                    save(topic, 10_000L + it, 0)
                    confirmMillis += (System.nanoTime() - begin) / 1_000_000
                    Thread.sleep(20)
                }
            },
            executor,
        )
        val drained = (0 until relays).map { index ->
            val round = roundOf(index)
            CompletableFuture.supplyAsync(
                {
                    start.await(10, SECONDS)
                    drain(round) { confirms.isDone }
                },
                executor,
            )
        }.map { it.get(120, SECONDS) }
        confirms.get(10, SECONDS)
        val marked = drained.map { it.marked }
        val deadlocks = drained.sumOf { it.deadlocks }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000

        val eventIds = receive(topic)
        val rows = outboxEventJpaRepository.findAll()
        return Result(
            name = name,
            relays = relays,
            rows = rows.size,
            kafkaRecords = eventIds.size,
            distinctEventIds = eventIds.toSet().size,
            duplicates = eventIds.size - eventIds.toSet().size,
            markedByRelay = marked.filterNotNull().takeIf { it.size == marked.size }
                ?: rows.groupingBy { it.claimedBy }.eachCount().values.sortedDescending(),
            unpublishedRows = rows.count { it.status != OutboxStatus.PUBLISHED },
            confirmInsertMaxMillis = confirmMillis.max(),
            confirmInsertAvgMillis = confirmMillis.average().toLong(),
            rowLockWaits = observer.rowLockWaits() - lockWaitsBefore,
            rowLockTimeMillis = observer.rowLockTimeMillis() - lockTimeBefore,
            deadlocks = deadlocks,
            latestDeadlock = if (deadlocks > 0) observer.latestDeadlock() else emptyList(),
            elapsedMillis = elapsed,
        )
    }

    private fun drain(round: () -> Stat, confirmsDone: () -> Boolean): Drained {
        var marked: Int? = 0
        var deadlocks = 0
        var idle = 0
        while (idle < 3) {
            val stat = round()
            marked = if (marked == null || stat.marked == null) null else marked + stat.marked
            deadlocks += stat.deadlocks
            if (stat.read == 0) {
                if (confirmsDone()) idle++
                Thread.sleep(20)
            } else {
                idle = 0
            }
        }
        return Drained(marked, deadlocks)
    }

    private fun delayed(delegate: OutboxPublisher) = object : OutboxPublisher {
        override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
            Thread.sleep(SEND_DELAY.toMillis())
            return delegate.publish(events)
        }
    }

    private fun save(topic: String, orderId: Long, sequence: Int) {
        outboxEventJpaRepository.save(
            OutboxEvent(
                eventId = "order-$orderId-$sequence",
                aggregateType = "ORDER",
                aggregateId = orderId.toString(),
                eventType = "OrderConfirmed",
                schemaVersion = 1,
                topic = topic,
                payload = """{"orderId":$orderId,"sequence":$sequence}""",
                now = ZonedDateTime.now(clock),
            ),
        )
    }

    private fun receive(topic: String): List<String> = KafkaConsumer<String, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
    ).use { consumer ->
        val partitions = listOf(TopicPartition(topic, 0), TopicPartition(topic, 1))
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        val ends = consumer.endOffsets(partitions)
        val eventIds = mutableListOf<String>()
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (partitions.any { consumer.position(it) < ends.getValue(it) } && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach { record ->
                eventIds += String(record.headers().lastHeader("eventId").value(), Charsets.UTF_8)
            }
        }
        eventIds
    }

    private fun saveEvidence(results: List<Result>) {
        val directory = Path.of("build", "reports", "week7")
        Files.createDirectories(directory)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            directory.resolve("relay-comparison.json").toFile(),
            mapOf(
                "observedAt" to Instant.now().toString(),
                "data" to mapOf(
                    "orders" to ORDERS,
                    "rowsPerOrder" to ROWS_PER_ORDER,
                    "confirmsDuringRun" to CONFIRMS,
                    "batchSize" to BATCH,
                    "sendDelayMillis" to SEND_DELAY.toMillis(),
                ),
                "scenarios" to results,
            ),
        )
    }

    private fun ExperimentRelays.Round.toStat() = Stat(read = read.size, marked = marked, deadlocks = deadlocks)

    data class Stat(val read: Int, val marked: Int?, val deadlocks: Int)

    data class Drained(val marked: Int?, val deadlocks: Int)

    data class Result(
        val name: String,
        val relays: Int,
        val rows: Int,
        val kafkaRecords: Int,
        val distinctEventIds: Int,
        val duplicates: Int,
        val markedByRelay: List<Int>,
        val unpublishedRows: Int,
        val confirmInsertMaxMillis: Long,
        val confirmInsertAvgMillis: Long,
        val rowLockWaits: Long,
        val rowLockTimeMillis: Long,
        val deadlocks: Int,
        val latestDeadlock: List<String>,
        val elapsedMillis: Long,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Topics {
        @Bean
        fun comparisonTopics(): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(*topics.values.map { NewTopic(it, 2, 1) }.toTypedArray())
    }
}
