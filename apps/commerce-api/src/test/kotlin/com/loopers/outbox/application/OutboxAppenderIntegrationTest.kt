package com.loopers.outbox.application

import com.loopers.order.domain.event.OrderConfirmed
import com.loopers.order.domain.event.OrderEventType
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonSerializer
import java.time.Instant

/** 엔티티 캐시가 아닌 DB 에 남은 값을 보려고 native query 로 조회 */
@SpringBootTest
@DisplayName("outbox append")
class OutboxAppenderIntegrationTest @Autowired constructor(
    private val outboxAppender: OutboxAppender,
    private val producerFactory: ProducerFactory<Any, Any>,
    private val entityManager: EntityManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val event = OrderConfirmed(
        orderId = 1L,
        confirmedAt = Instant.parse("2026-11-05T03:54:37.123456Z"),
        items = listOf(OrderConfirmed.Item(productId = 9001L, quantity = 2), OrderConfirmed.Item(productId = 9002L, quantity = 1)),
    )

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun append(eventId: String = event.eventId) = outboxAppender.append(
        eventId = eventId,
        aggregateType = "ORDER",
        aggregateId = event.orderId.toString(),
        eventType = event.eventType.eventName,
        schemaVersion = event.schemaVersion,
        topic = OrderEventType.TOPIC,
        payload = event,
    )

    private fun rows(): List<Array<*>> = entityManager.createNativeQuery(
        """
        SELECT event_id, aggregate_type, aggregate_id, event_type, schema_version, topic,
               status, attempt_count, next_attempt_at, claimed_by, claimed_until, published_at
        FROM outbox
        """.trimIndent(),
    ).resultList.map { it as Array<*> }

    private fun storedPayload(): String =
        entityManager.createNativeQuery("SELECT payload FROM outbox").singleResult as String

    @DisplayName("append 하면 넘긴 값 그대로 PENDING 행이 하나 생긴다.")
    @Test
    fun createsPendingRow() {
        // act
        append()

        // assert
        val rows = rows()
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertAll(
            { assertThat(row[0]).isEqualTo("order-confirmed:v1:1") },
            { assertThat(row[1]).isEqualTo("ORDER") },
            { assertThat(row[2]).isEqualTo("1") },
            { assertThat(row[3]).isEqualTo("OrderConfirmed") },
            { assertThat((row[4] as Number).toInt()).isEqualTo(1) },
            { assertThat(row[5]).isEqualTo("order-events") },
            { assertThat(row[6]).isEqualTo(OutboxStatus.PENDING.name) },
            { assertThat((row[7] as Number).toInt()).isZero() },
            { assertThat(row[8]).isNotNull() },
            { assertThat(row[9]).isNull() },
            { assertThat(row[10]).isNull() },
            { assertThat(row[11]).isNull() },
        )
    }

    @DisplayName("같은 eventId 를 두 번 append 하면 unique 위반이 나고 행은 하나만 남는다.")
    @Test
    fun rejectsDuplicateEventId() {
        // arrange
        append()

        // act & assert
        assertThrows<DataIntegrityViolationException> { append() }
        assertThat(rows()).hasSize(1)
    }

    @DisplayName("저장된 payload 는 지금 Kafka 로 나가는 본문과 글자 하나까지 같다.")
    @Test
    fun storesPayloadAsKafkaSendsIt() {
        // arrange
        val serializer = JsonSerializer<Any>().apply { configure(producerFactory.configurationProperties, false) }
        val sentByKafka = String(serializer.serialize(OrderEventType.TOPIC, event)!!, Charsets.UTF_8)

        // act
        append()

        // assert
        assertThat(storedPayload()).isEqualTo(sentByKafka)
    }
}
