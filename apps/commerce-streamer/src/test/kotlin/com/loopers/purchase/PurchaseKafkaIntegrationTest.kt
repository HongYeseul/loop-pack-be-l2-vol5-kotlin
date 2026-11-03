package com.loopers.purchase

import com.loopers.purchase.application.PurchaseActivityService
import com.loopers.purchase.domain.OrderSourceRepository
import com.loopers.purchase.infrastructure.PurchaseActivityJpaRepository
import com.loopers.config.OrderEventJsonDeserializer
import com.loopers.purchase.interfaces.consumer.PurchaseActivityListener
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.testcontainers.MySqlTestContainersConfig
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.kafka.admin.auto-create=true", "spring.kafka.listener.auto-startup=false", "spring.jpa.show-sql=false"],
)
@Import(MySqlTestContainersConfig::class, PurchaseKafkaIntegrationTest.Topics::class)
@Testcontainers
@DisplayName("실제 Kafka·MySQL에서 구매 활동 수신·재시도·실패 복구 검증")
class PurchaseKafkaIntegrationTest @Autowired constructor(
    @Qualifier("purchaseDltTemplate") private val raw: KafkaTemplate<String, ByteArray>,
    private val registry: KafkaListenerEndpointRegistry,
    private val jpa: PurchaseActivityJpaRepository,
    private val jdbc: JdbcTemplate,
    private val source: OrderSourceRepository,
) {
    @MockitoSpyBean
    private lateinit var service: PurchaseActivityService

    @MockitoSpyBean
    private lateinit var publisher: DeadLetterPublishingRecoverer

    @MockitoSpyBean(name = "purchaseDltTemplate")
    private lateinit var dltTemplate: KafkaTemplate<String, ByteArray>

    @MockitoSpyBean
    private lateinit var listener: PurchaseActivityListener

    companion object {
        private val topic = "purchase-test-${UUID.randomUUID()}"
        private val dlt = "$topic.DLT"
        private val group = "purchase-test-${UUID.randomUUID()}"

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @DynamicPropertySource
        @JvmStatic
        fun properties(properties: DynamicPropertyRegistry) {
            properties.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            properties.add("commerce.kafka.order-topic") { topic }
            properties.add("commerce.kafka.order-dlt-topic") { dlt }
            properties.add("spring.kafka.consumer.group-id") { group }
        }
    }

    private val container get() = requireNotNull(registry.getListenerContainer("purchase-activity"))
    private val partition = TopicPartition(topic, 0)

    @BeforeEach
    fun prepare() {
        container.stop()
        jpa.deleteAllInBatch()
        jdbc.execute("CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY, status VARCHAR(20), confirmed_at DATETIME(6))")
        jdbc.execute("CREATE TABLE IF NOT EXISTS order_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, product_id BIGINT, quantity INT)")
        jdbc.update("DELETE FROM order_item")
        jdbc.update("DELETE FROM orders")
        admin().use { admin ->
            val offsets = admin.listOffsets((0..1).associate { TopicPartition(topic, it) to OffsetSpec.latest() }).all().get(10, SECONDS)
            admin.alterConsumerGroupOffsets(group, offsets.mapValues { OffsetAndMetadata(it.value.offset()) }).all().get(10, SECONDS)
        }
    }

    @AfterEach
    fun stop() = container.stop()

    @Test
    @DisplayName("구매 활동을 저장한 뒤 ack한 record의 다음 offset을 커밋한다")
    fun commitsAfterDatabaseSave() {
        val record = send(101)
        start()

        awaitCommit(record.offset() + 1)

        assertThat(PurchaseEvents.purchase(101).matches(jpa.findAllByOrderIdOrderByProductIdAsc(101))).isTrue()
        assertThat(jpa.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 이벤트가 다시 전달돼도 구매 활동을 추가하지 않고 ack한다")
    fun acceptsDuplicateDelivery() {
        val first = send(101)
        start()
        awaitCommit(first.offset() + 1)

        val repeated = send(101)
        awaitCommit(repeated.offset() + 1)

        assertThat(PurchaseEvents.purchase(101).matches(jpa.findAllByOrderIdOrderByProductIdAsc(101))).isTrue()
        assertThat(jpa.count()).isEqualTo(2)
        assertThat(first.offset()).isLessThan(repeated.offset())
    }

    @Test
    @DisplayName("DB 커밋 후 ack 전에 소비를 중단해도 같은 group으로 재시작하면 중복 저장하지 않는다")
    fun restartsSameGroupAfterDatabaseCommitBeforeAck() {
        val purchase = PurchaseEvents.purchase(102)
        Mockito.doAnswer { invocation ->
            invocation.callRealMethod()
            throw IllegalStateException("DB 커밋 직후 ack 전 중단 실험")
        }.`when`(service).record(purchase)
        val record = send(102)
        start()
        await().atMost(Duration.ofSeconds(20)).until { !container.isRunning }
        assertThat(jpa.count()).isEqualTo(2)
        assertThat(committed()).isLessThanOrEqualTo(record.offset())
        container.stop()
        Mockito.reset(service)
        start()
        awaitCommit(record.offset() + 1)
        assertThat(jpa.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("일시적 처리 오류가 두 번 발생하면 재시도 후 구매 활동을 저장하고 ack한다")
    fun retriesTransientFailureInFreshTransactionThenSucceeds() {
        val attempts = AtomicInteger()
        Mockito.doAnswer { invocation ->
            if (attempts.incrementAndGet() <= 2) throw DataAccessResourceFailureException("일시적인 DB 장애")
            invocation.callRealMethod()
        }.`when`(service).record(PurchaseEvents.purchase(103))
        val record = send(103)
        start()
        awaitCommit(record.offset() + 1)
        assertThat(attempts.get()).isEqualTo(3)
        assertThat(jpa.count()).isEqualTo(2)
        Mockito.verify(publisher, Mockito.never()).accept(any(), any())
    }

    @Test
    @DisplayName("최초 실행과 재시도 3회가 모두 실패하면 원문을 DLT에 보관하고 다음 메시지를 처리한다")
    fun exhaustsFourAttemptsThenPreservesBytesInDltAndContinues() {
        val attempts = AtomicInteger()
        val times = mutableListOf<Long>()
        Mockito.doAnswer {
            attempts.incrementAndGet()
            synchronized(times) { times.add(System.nanoTime()) }
            throw DataAccessResourceFailureException("지속적인 DB 장애")
        }.`when`(service).record(PurchaseEvents.purchase(104))
        val failed = send(104)
        val next = send(105)
        start()
        awaitCommit(next.offset() + 1)
        val dead = readDlt("104")
        assertThat(dead.value()).isEqualTo(PurchaseEvents.payload(104))
        assertThat(dead.partition()).isEqualTo(0)
        assertThat(dead.headers().lastHeader("kafka_dlt-original-topic").value().toString(Charsets.UTF_8)).isEqualTo(topic)
        assertThat(failed.offset()).isLessThan(next.offset())
        assertThat(attempts.get()).isEqualTo(4)
        val intervals = synchronized(times) { times.zipWithNext { a, b -> Duration.ofNanos(b - a).toMillis() } }
        assertThat(intervals[0]).isGreaterThanOrEqualTo(900)
        assertThat(intervals[1]).isGreaterThanOrEqualTo(1_900)
        assertThat(intervals[2]).isGreaterThanOrEqualTo(3_900)
        assertThat(jpa.findAll().map { it.orderId }.distinct()).containsExactly(105L)
    }

    @Test
    @DisplayName("잘못된 JSON은 업무 처리를 호출하지 않고 원문을 DLT로 보낸다")
    fun invalidJsonGoesDirectlyToDlt() {
        val value = "broken{".toByteArray()
        val failed = raw.send(topic, 0, "bad-json", value).get(10, SECONDS).recordMetadata
        start()

        awaitCommit(failed.offset() + 1)

        assertThat(readDlt("bad-json").value()).isEqualTo(value)
        Mockito.verify(service, Mockito.never()).record(any())
    }

    @Test
    @DisplayName("기존 구매 내용과 충돌하면 재시도 없이 DLT로 보내고 기존 기록을 유지한다")
    fun contentConflictGoesDirectlyToDlt() {
        val original = PurchaseEvents.purchase(106)
        service.record(original)
        val conflict = raw.send(topic, 0, "106", PurchaseEvents.payload(106, 3)).get(10, SECONDS).recordMetadata
        start()

        awaitCommit(conflict.offset() + 1)

        assertThat(readDlt("106").value()).isEqualTo(PurchaseEvents.payload(106, 3))
        Mockito.verify(service, Mockito.times(1)).record(PurchaseEvents.purchase(106, 3))
        assertThat(original.matches(jpa.findAllByOrderIdOrderByProductIdAsc(106))).isTrue()
    }

    @Test
    @DisplayName("DLT 전송에 실패해도 주문 원본이 일치하면 offset을 넘기고 다음 메시지를 소비한다")
    fun failedDltWithMatchingSourceContinues() {
        seedOrder(107)
        Mockito.doThrow(DataAccessResourceFailureException("구매 활동 저장 실패")).`when`(service).record(PurchaseEvents.purchase(107))
        failDlt()
        send(107)
        val next = send(108)
        start()

        awaitCommit(next.offset() + 1)

        assertThat(missingOrders()).containsExactly(107L)
        assertThat(jpa.findAllByOrderIdOrderByProductIdAsc(107)).isEmpty()
        assertThat(PurchaseEvents.purchase(108).matches(jpa.findAllByOrderIdOrderByProductIdAsc(108))).isTrue()
    }

    @Test
    @DisplayName("이미 소비를 건너뛴 주문을 원본에서 수동 재발행하면 누락된 구매 활동을 복구한다")
    fun manualReplayRepairsSkippedPurchase() {
        prepareSkippedOrder(107)

        val replay = replayFromSource(107)
        awaitCommit(replay.offset() + 1)

        assertThat(missingOrders()).isEmpty()
        assertThat(PurchaseEvents.purchase(107).matches(jpa.findAllByOrderIdOrderByProductIdAsc(107))).isTrue()
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=107", String::class.java)).isEqualTo("CONFIRMED")
    }

    @Test
    @DisplayName("수동 복구한 주문을 다시 재발행해도 구매 활동은 한 번만 저장한다")
    fun repeatedManualReplayDoesNotDuplicatePurchase() {
        prepareSkippedOrder(107)
        val first = replayFromSource(107)
        awaitCommit(first.offset() + 1)

        val repeated = replayFromSource(107)
        awaitCommit(repeated.offset() + 1)

        assertThat(jpa.findAllByOrderIdOrderByProductIdAsc(107)).hasSize(2)
        assertThat(PurchaseEvents.purchase(107).matches(jpa.findAllByOrderIdOrderByProductIdAsc(107))).isTrue()
    }

    @Test
    @DisplayName("이벤트가 발행되지 않은 확정 주문을 찾아 원본으로 재발행하면 구매 활동이 저장된다")
    fun unpublishedOrderIsDiscoveredAndReplayedFromSource() {
        seedOrder(109)
        assertThat(missingOrders()).containsExactly(109L)
        start()
        val replay = replayFromSource(109)
        awaitCommit(replay.offset() + 1)
        assertThat(missingOrders()).isEmpty()
        assertThat(PurchaseEvents.purchase(109).matches(jpa.findAllByOrderIdOrderByProductIdAsc(109))).isTrue()
    }

    @Test
    @DisplayName("DLT 전송에 실패하고 주문 원본의 수량이 다르면 offset을 유지하고 소비를 중단한다")
    fun failedDltAndMismatchingSourceStopWithoutAdvancingOffset() {
        seedOrder(110, quantity = 9)
        Mockito.doThrow(DataAccessResourceFailureException("구매 활동 저장 실패")).`when`(service).record(PurchaseEvents.purchase(110))
        failDlt()
        val failed = send(110)
        send(111)
        start()
        await().atMost(Duration.ofSeconds(25)).until { !container.isRunning }
        assertThat(committed()).isLessThanOrEqualTo(failed.offset())
        assertThat(jpa.count()).isZero()
    }

    @Test
    @DisplayName("DLT 전송에 실패하고 주문 원본이 없으면 offset을 유지하고 소비를 중단한다")
    fun failedDltAndUnavailableSourceStopWithoutAdvancingOffset() {
        Mockito.doThrow(DataAccessResourceFailureException("구매 활동 저장 실패")).`when`(service).record(PurchaseEvents.purchase(112))
        failDlt()
        val failed = send(112)
        send(113)
        start()
        await().atMost(Duration.ofSeconds(25)).until { !container.isRunning }
        assertThat(committed()).isLessThanOrEqualTo(failed.offset())
        assertThat(jpa.count()).isZero()
    }

    @Test
    @DisplayName("잘못된 JSON의 DLT 전송도 실패하면 offset을 유지하고 소비를 중단한다")
    fun invalidMessageCannotUseSourceFallbackWhenDltFails() {
        failDlt()
        val failed = raw.send(topic, 0, "broken", "{".toByteArray()).get(10, SECONDS).recordMetadata
        send(114)
        start()
        await().atMost(Duration.ofSeconds(20)).until { !container.isRunning }
        assertThat(committed()).isLessThanOrEqualTo(failed.offset())
        assertThat(jpa.count()).isZero()
    }

    @Test
    @DisplayName("주문 확정 외 이벤트는 구매 활동을 저장하지 않고 ack한다")
    fun otherEventTypesAreAcknowledgedWithoutDatabaseWrites() {
        val record = raw.send(topic, 0, "115", "{\"eventType\":\"PaymentSucceeded\"}".toByteArray()).get(10, SECONDS).recordMetadata
        start()
        awaitCommit(record.offset() + 1)
        assertThat(jpa.count()).isZero()
    }

    @Test
    @DisplayName("주문 확정 이벤트의 추가 필드를 무시하고 구매 활동을 저장한다")
    fun bindsAdditionalFields() {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload(116)) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("paymentId", "payment-116")
        (tree.path("items")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("productName", "상품")
        val record = raw.send(topic, 0, "116", PurchaseEvents.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree)).get(10, SECONDS).recordMetadata
        start()

        awaitCommit(record.offset() + 1)

        assertThat(PurchaseEvents.purchase(116).matches(jpa.findAllByOrderIdOrderByProductIdAsc(116))).isTrue()
        assertThat(jpa.count()).isEqualTo(2)
        Mockito.verify(publisher, Mockito.never()).accept(any(), any())
    }

    @Test
    @DisplayName("다른 구조의 이벤트는 구매 검증 없이 건너뛰고 ack한다")
    fun skipsDifferentEventShapes() {
        val other = """{"eventType":"PaymentSucceeded","orderId":"external-id","items":{"payment":true},"confirmedAt":123}"""
        val record = raw.send(topic, 0, "external-id", other.toByteArray()).get(10, SECONDS).recordMetadata
        start()

        awaitCommit(record.offset() + 1)

        assertThat(jpa.count()).isZero()
        Mockito.verify(service, Mockito.never()).record(any())
        Mockito.verify(publisher, Mockito.never()).accept(any(), any())
    }

    @Test
    @DisplayName("DTO 변환 후 계약 검증에 실패해도 공백·추가 필드를 포함한 원문을 DLT에 그대로 보관한다")
    fun preservesOriginalBytesAfterValidationFailure() {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload(117, 0)) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("extra", "원문 보존 확인")
        val value = PurchaseEvents.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree)
        val outbound = ProducerRecord(topic, 0, "117", value)
        outbound.headers().add(OrderEventJsonDeserializer.ORIGINAL_VALUE_HEADER, "forged".toByteArray())
        val record = raw.send(outbound).get(10, SECONDS).recordMetadata
        start()
        awaitCommit(record.offset() + 1)
        val dead = readDlt("117")
        assertThat(dead.value()).isEqualTo(value)
        assertThat(dead.headers().lastHeader(OrderEventJsonDeserializer.ORIGINAL_VALUE_HEADER)).isNull()
        assertThat(jpa.count()).isZero()
        Mockito.verify(service, Mockito.never()).record(any())
    }

    @Test
    @DisplayName("필드 자료형이 잘못되면 listener를 호출하지 않고 역직렬화 오류와 원문을 DLT에 보관한다")
    fun deserializationFailureGoesToDltBeforeListener() {
        val tree = PurchaseEvents.mapper.readTree(PurchaseEvents.payload(118)) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("orderId", "not-a-number")
        val value = PurchaseEvents.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree)
        val record = raw.send(topic, 0, "118", value).get(10, SECONDS).recordMetadata
        start()
        awaitCommit(record.offset() + 1)
        assertThat(readDlt("118").value()).isEqualTo(value)
        assertThat(jpa.count()).isZero()
        Mockito.verify(listener, Mockito.never()).receive(any(), any())
        Mockito.verify(service, Mockito.never()).record(any())
    }

    private fun start() {
        container.start()
        await().atMost(Duration.ofSeconds(20)).until { container.assignedPartitions?.size == 2 || !container.isRunning }
    }

    private fun send(orderId: Long) = raw.send(topic, 0, orderId.toString(), PurchaseEvents.payload(orderId)).get(10, SECONDS).recordMetadata

    private fun failDlt() {
        Mockito.doAnswer { invocation ->
            val record = invocation.getArgument<ProducerRecord<String, ByteArray>>(0)
            if (record.topic() == dlt) {
                CompletableFuture.failedFuture<SendResult<String, ByteArray>>(IllegalStateException("DLT 비동기 전송 실패 실험"))
            } else {
                invocation.callRealMethod()
            }
        }.`when`(dltTemplate).send(any<ProducerRecord<String, ByteArray>>())
    }

    private fun admin() = AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers))

    private fun committed(): Long = admin().use {
        it.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, SECONDS)[partition]?.offset() ?: -1
    }

    private fun awaitCommit(offset: Long) {
        await().atMost(Duration.ofSeconds(25)).untilAsserted { assertThat(committed()).isEqualTo(offset) }
    }

    private fun prepareSkippedOrder(orderId: Long) {
        seedOrder(orderId)
        val skipped = send(orderId)
        admin().use { admin ->
            admin.alterConsumerGroupOffsets(group, mapOf(partition to OffsetAndMetadata(skipped.offset() + 1))).all().get(10, SECONDS)
        }
        start()
    }

    private fun seedOrder(orderId: Long, quantity: Int = 2) {
        jdbc.update("INSERT INTO orders(id,status,confirmed_at) VALUES (?, 'CONFIRMED', '2026-10-27 01:02:03.123456')", orderId)
        jdbc.update("INSERT INTO order_item(order_id, product_id, quantity) VALUES (?,7,?),(?,8,1)", orderId, quantity, orderId)
    }

    private fun missingOrders(): List<Long> = jdbc.queryForList(
        "SELECT o.id FROM orders o WHERE o.status='CONFIRMED' AND NOT EXISTS (SELECT 1 FROM purchase_activity p WHERE p.order_id=o.id) ORDER BY o.id",
        Long::class.java,
    )

    private fun replayFromSource(orderId: Long): org.apache.kafka.clients.producer.RecordMetadata {
        val purchase = requireNotNull(source.findConfirmed(orderId))
        val payload = PurchaseEvents.mapper.writeValueAsBytes(
            mapOf(
                "eventType" to "OrderConfirmed",
                "schemaVersion" to 1,
                "eventId" to purchase.eventId,
                "orderId" to purchase.orderId,
                "confirmedAt" to purchase.confirmedAt.toString(),
                "items" to purchase.items,
            ),
        )
        return raw.send(topic, 0, orderId.toString(), payload).get(10, SECONDS).recordMetadata
    }

    private fun readDlt(key: String): ConsumerRecord<String, ByteArray> {
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "dlt-probe-${UUID.randomUUID()}",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            StringDeserializer(),
            ByteArrayDeserializer(),
        ).use { consumer ->
            consumer.assign((0..1).map { TopicPartition(dlt, it) })
            val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).firstOrNull { it.key() == key }?.let { return it }
            }
        }
        error("DLT에서 key=$key 메시지를 찾지 못했습니다.")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Topics {
        @Bean
        fun purchaseTestTopic() = NewTopic(topic, 2, 1)

        @Bean
        fun purchaseTestDlt() = NewTopic(dlt, 2, 1)
    }
}
