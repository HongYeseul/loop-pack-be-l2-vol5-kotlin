package com.loopers.outbox.application

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.TopicReader
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
    private lateinit var broker: TopicReader

    @BeforeEach
    fun setUp() {
        broker = TopicReader(kafka.bootstrapServers, topic)
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        broker.close()
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("두 대가 같은 순간에 행 10개를 선점하면,")
    @Nested
    inner class WhenTwoRelaysClaimAtOnce {
        private lateinit var eventIds: List<String>
        private lateinit var picked: List<Int>

        @BeforeEach
        fun bothRelaysRunOnce() {
            eventIds = (1L..10L).map { save(it) }
            // 두 relay 가 모두 선점을 끝낸 뒤에 보내게 맞춤
            val bothClaimed = CyclicBarrier(2)
            picked = listOf("relay-a", "relay-b")
                .map { name -> CompletableFuture.supplyAsync({ relay(name, waitingAt = bothClaimed).runOnce() }, executor) }
                .map { it.get(30, SECONDS) }
        }

        @Test
        @DisplayName("배치 크기(5)만큼 겹치지 않게 나눠 가져간다")
        fun splitsRows() {
            assertThat(picked).containsExactly(5, 5)
            assertThat(outboxEventJpaRepository.findAll().groupingBy { it.claimedBy }.eachCount())
                .isEqualTo(mapOf("relay-a" to 5, "relay-b" to 5))
        }

        @Test
        @DisplayName("행마다 브로커에 한 번씩만 나간다")
        fun sendsEachRowOnce() {
            assertThat(broker.read(count = 10).map { it.eventId }).containsExactlyInAnyOrderElementsOf(eventIds)
        }
    }

    @Test
    @DisplayName("다른 relay 가 선점하느라 잠근 행은 기다리지 않고 건너뛰어 나머지를 가져간다")
    fun skipsRowsLockedByOtherRelay() {
        // arrange
        val eventIds = (1L..10L).map { save(it) }
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val relayA = CompletableFuture.runAsync({ holdClaim("relay-a", rows = 5, locked, release) }, executor)
        assertThat(locked.await(10, SECONDS)).isTrue()

        // act: relay A 가 잠금을 쥔 동안 relay B 가 한 회차를 돌고, 기다리면 2초 안에 끝나지 않음
        val picked = CompletableFuture.supplyAsync({ relay("relay-b").runOnce() }, executor).get(2, SECONDS)
        release.countDown()
        relayA.get(10, SECONDS)

        // assert
        assertThat(picked).isEqualTo(5)
        assertThat(broker.read(count = 5).map { it.eventId }).containsExactlyInAnyOrderElementsOf(eventIds.drop(5))
    }

    /** relay 의 선점 트랜잭션을 커밋하지 않고 붙잡아 둠 */
    private fun holdClaim(name: String, rows: Int, locked: CountDownLatch, release: CountDownLatch) {
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }.executeWithoutResult {
            val now = ZonedDateTime.now(clock)
            outboxEventRepository.claim(name, now, now.plus(properties.lease), rows, properties.candidateSize)
            locked.countDown()
            release.await(30, SECONDS)
        }
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

    @TestConfiguration(proxyBeanMethods = false)
    class TopicConfig {
        @Bean
        fun concurrencyTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
