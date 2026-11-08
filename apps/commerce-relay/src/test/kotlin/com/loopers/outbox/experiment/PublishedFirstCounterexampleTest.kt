package com.loopers.outbox.experiment

import com.loopers.outbox.application.OutboxRelay
import com.loopers.outbox.application.OutboxRelayProperties
import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.utils.TopicReader
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.ZonedDateTime
import java.util.UUID

/**
 * "보내기 전에 PUBLISHED 를 먼저 저장하면 중복이 줄어든다"는 제안의 반례.
 * 지금 relay 는 보낸 뒤 기록하므로 같은 지점에서 멈춰도 다시 보냄 (OutboxRelayPublicationTest 의 재시작 사례)
 */
@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true"])
@Import(PublishedFirstCounterexampleTest.TopicConfig::class)
@Testcontainers
@DisplayName("PUBLISHED 를 먼저 저장하고 보내는 relay 의 반례")
class PublishedFirstCounterexampleTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val properties: OutboxRelayProperties,
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

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @Test
    @DisplayName("PUBLISHED 를 먼저 저장하고 보내기 전에 멈추면, 브로커에는 아무것도 없는데 행은 PUBLISHED 로 남는다")
    fun leavesPublishedRowWithoutBrokerRecord() {
        val id = save("order-confirmed:v1:1")

        TopicReader(kafka.bootstrapServers, topic).use { broker ->
            markPublishedThenStopBeforeSend()

            assertThat(broker.read(count = 1)).isEmpty()
            assertThat(outboxEventJpaRepository.findById(id).get().status).isEqualTo(OutboxStatus.PUBLISHED)
        }
    }

    @Test
    @DisplayName("그 행은 더 이상 보낼 대상이 아니어서, relay 를 다시 돌려도 끝내 보내지 않는다")
    fun neverSendsThatEventAgain() {
        save("order-confirmed:v1:1")
        markPublishedThenStopBeforeSend()

        TopicReader(kafka.bootstrapServers, topic).use { broker ->
            val picked = outboxRelay.runOnce()

            assertThat(picked).isZero()
            assertThat(broker.read(count = 1)).isEmpty()
        }
    }

    // 제안대로 한 relay: 선점과 함께 PUBLISHED 를 저장하고 커밋한 뒤 보내려다 멈춤 (보내는 단계가 실행되지 않음)
    private fun markPublishedThenStopBeforeSend() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            val now = ZonedDateTime.now()
            val claimed = outboxEventRepository.claim("relay-published-first", now, now.plus(properties.lease), 10, properties.candidateSize)
            outboxEventRepository.markPublished(claimed.map { it.id }, "relay-published-first", now)
        }
    }

    private fun save(eventId: String): Long = outboxEventJpaRepository.save(
        OutboxEvent(
            eventId = eventId,
            aggregateType = "ORDER",
            aggregateId = "1",
            eventType = "OrderConfirmed",
            schemaVersion = 1,
            topic = topic,
            payload = """{"eventId":"$eventId"}""",
            now = ZonedDateTime.now(),
        ),
    ).id

    @TestConfiguration(proxyBeanMethods = false)
    class TopicConfig {
        @Bean
        fun counterexampleTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
