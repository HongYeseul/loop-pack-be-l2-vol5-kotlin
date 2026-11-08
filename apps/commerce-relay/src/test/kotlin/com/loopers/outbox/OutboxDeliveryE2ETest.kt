package com.loopers.outbox

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
import com.loopers.utils.AppProcesses
import com.loopers.utils.TopicReader
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.GroupIdNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 실제 주문 API(jar)로 확정한 주문이 relay 중단과 재시작을 거쳐 실제 consumer(jar)의 구매 활동이 되는 한 번의 실행.
 * 시나리오는 한 번만 돌리고, 테스트마다 요구사항 하나를 확인. 결과는 build/reports/week7/e2e-delivery.json
 */
@SpringBootTest(properties = ["spring.kafka.admin.auto-create=true"])
@Import(OutboxDeliveryE2ETest.TopicConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("주문 확정부터 구매 활동까지, relay 가 첫 발행 뒤 멈췄다가 다시 뜨는 한 번의 실행")
class OutboxDeliveryE2ETest @Autowired constructor(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val kafkaOutboxPublisher: OutboxPublisher,
    private val properties: OutboxRelayProperties,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val environment: Environment,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val topic = "order-events-e2e-${UUID.randomUUID()}"
        private const val BUYER = "e2ebuyer"
        private const val PRODUCT_A = 7L
        private const val PRODUCT_B = 8L

        // 테스트 인스턴스를 클래스당 하나 쓰면 Spring 컨텍스트가 @Container 시작보다 먼저 떠서 직접 시작
        private val kafka = KafkaTestFixture().apply { start() }
        private val redis = GenericContainer(DockerImageName.parse("redis:latest")).withExposedPorts(6379).apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    private val group = "purchase-e2e-${UUID.randomUUID()}"
    private val evidenceDirectory: Path = Path.of(System.getProperty("week7.evidence-directory"))
    private val apiPort = freePort()
    private val http = HttpClient.newHttpClient()
    private lateinit var apps: AppProcesses
    private lateinit var run: Run

    data class Snapshot(
        val orderStatus: String,
        val stocks: Map<Long, Int>,
        val pointBalance: Long,
        val outbox: List<OutboxRow>,
        val purchaseActivity: List<Pair<Long, Int>>,
    )

    data class OutboxRow(val eventId: String, val status: OutboxStatus, val publishedAt: Instant?, val claimedBy: String?)

    data class Run(
        val orderId: Long,
        val afterConfirm: Snapshot,
        val afterStop: Snapshot,
        val atEnd: Snapshot,
        val brokerRecords: List<TopicReader.Record>,
        val consumerCommittedOffset: Long?,
    ) {
        val eventId get() = "order-confirmed:v1:$orderId"
    }

    @BeforeAll
    fun runOnce() {
        // 1. 실제 주문 API 와 구매 활동 consumer 를 띄움
        apps = AppProcesses(evidenceDirectory.resolve("e2e"), commonAppOptions())
        startApi()
        seedBuyerAndProducts()
        startConsumer()

        TopicReader(kafka.bootstrapServers, topic).use { broker ->
            // 2. 주문을 만들어 확정
            chargePoints(100_000)
            val orderId = createOrder(PRODUCT_A to 2, PRODUCT_B to 1)
            confirm(orderId)
            val afterConfirm = snapshot(orderId)

            // 3. relay 가 첫 발행을 브로커에 보낸 뒤, 결과를 기록하기 전에 멈춤
            assertThrows<StoppedBeforeRecord> { relay("relay-stopped", stopAfterBrokerAck(), Clock.systemUTC()).runOnce() }
            val afterStop = snapshot(orderId)

            // 4. relay 를 새로 띄움. lease 가 지난 시점이라 같은 행을 다시 가져가 보냄
            relay("relay-restarted", kafkaOutboxPublisher, Clock.offset(Clock.systemUTC(), properties.lease.plusSeconds(1))).runOnce()
            val brokerRecords = broker.read(count = 2)

            // 5. consumer 가 두 레코드를 모두 처리할 때까지 기다림
            val committed = awaitConsumerPassed(brokerRecords.last())
            run = Run(orderId, afterConfirm, afterStop, snapshot(orderId), brokerRecords, committed)
        }
        saveEvidence()
    }

    @AfterAll
    fun tearDown() {
        apps.close()
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("확정하면 주문은 CONFIRMED 가 되고, 같은 eventId 의 outbox 가 PENDING 한 행 생긴다")
    fun confirmLeavesOneOutboxRow() {
        assertThat(run.afterConfirm.orderStatus).isEqualTo("CONFIRMED")
        assertThat(run.afterConfirm.outbox.map { it.eventId to it.status }).containsExactly(run.eventId to OutboxStatus.PENDING)
    }

    @Test
    @DisplayName("첫 발행을 브로커가 받은 뒤 결과 기록 전에 멈추면, outbox 는 발행 시각 없이 CLAIMED 로 남는다")
    fun stopAfterBrokerAckLeavesClaimedRow() {
        val row = run.afterStop.outbox.single()
        assertThat(row.status).isEqualTo(OutboxStatus.CLAIMED)
        assertThat(row.publishedAt).isNull()
    }

    @Test
    @DisplayName("다시 띄운 relay 가 같은 사건을 또 보내, 브로커에 같은 eventId 가 서로 다른 offset 으로 두 번 남는다")
    fun brokerHasSameEventTwice() {
        assertThat(run.brokerRecords.map { it.eventId }).containsExactly(run.eventId, run.eventId)
        assertThat(run.brokerRecords.map { it.offset }.distinct()).hasSize(2)
    }

    @Test
    @DisplayName("다시 보낸 relay 가 결과를 기록해 outbox 는 PUBLISHED 가 된다")
    fun outboxEndsPublished() {
        assertThat(run.atEnd.outbox.single().status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(run.atEnd.outbox.single().claimedBy).isEqualTo("relay-restarted")
    }

    @Test
    @DisplayName("consumer 는 두 레코드를 모두 처리하고 그 다음 offset 까지 커밋한다")
    fun consumerProcessesBothRecords() {
        assertThat(run.consumerCommittedOffset).isEqualTo(run.brokerRecords.last().offset + 1)
    }

    @Test
    @DisplayName("같은 사건을 두 번 받아도 구매 활동은 상품별 한 행씩, 수량 2와 1 이다")
    fun purchaseActivityIsRecordedOnce() {
        assertThat(run.atEnd.purchaseActivity).containsExactly(PRODUCT_A to 2, PRODUCT_B to 1)
    }

    @Test
    @DisplayName("relay 가 멈췄다 다시 보내는 동안 주문 상태, 재고, 포인트는 바뀌지 않는다")
    fun redeliveryDoesNotChangeOrderStockOrPoints() {
        assertThat(run.atEnd.copy(outbox = emptyList(), purchaseActivity = emptyList()))
            .isEqualTo(run.afterConfirm.copy(outbox = emptyList(), purchaseActivity = emptyList()))
    }

    // ---- relay

    class StoppedBeforeRecord : RuntimeException("브로커 확인 뒤 결과 기록 전 중단")

    private fun stopAfterBrokerAck() = object : OutboxPublisher {
        override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
            check(kafkaOutboxPublisher.publish(events).all { it is OutboxPublishResult.Published })
            throw StoppedBeforeRecord()
        }
    }

    private fun relay(name: String, publisher: OutboxPublisher, clock: Clock) =
        OutboxRelay(outboxEventRepository, publisher, properties, clock, transactionManager, OutboxRelayName(name))

    // ---- 앱과 주문

    private fun commonAppOptions() = listOf(
        "--spring.profiles.active=test",
        "--spring.jpa.show-sql=false",
        "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
        "--commerce.kafka.order-topic=$topic",
        "--datasource.mysql-jpa.main.jdbc-url=${environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url")}",
        "--datasource.mysql-jpa.main.username=${environment.getRequiredProperty("datasource.mysql-jpa.main.username")}",
        "--datasource.mysql-jpa.main.password=${environment.getRequiredProperty("datasource.mysql-jpa.main.password")}",
        "--datasource.mysql-jpa.main.maximum-pool-size=3",
        "--datasource.mysql-jpa.main.minimum-idle=1",
    )

    private fun startApi() {
        apps.start(
            "api",
            System.getProperty("week7.api-jar"),
            "--server.port=$apiPort",
            "--management.server.port=${freePort()}",
            "--spring.jpa.hibernate.ddl-auto=update",
            "--datasource.redis.master.host=${redis.host}",
            "--datasource.redis.master.port=${redis.firstMappedPort}",
            "--datasource.redis.replicas[0].host=${redis.host}",
            "--datasource.redis.replicas[0].port=${redis.firstMappedPort}",
        )
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted {
            apps.assertAlive()
            assertThat(get("/api/v1/orders/0").statusCode()).isPositive()
        }
    }

    private fun startConsumer() {
        val partitions = listOf(TopicPartition(topic, 0), TopicPartition(topic, 1))
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(5, TimeUnit.SECONDS)
            admin.alterConsumerGroupOffsets(group, ends.mapValues { OffsetAndMetadata(it.value.offset()) }).all().get(5, TimeUnit.SECONDS)
            apps.start(
                "streamer",
                System.getProperty("week7.streamer-jar"),
                "--spring.main.web-application-type=none",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.kafka.consumer.group-id=$group",
                "--spring.kafka.listener.auto-startup=true",
            )
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .ignoreExceptionsMatching { error -> generateSequence(error) { it.cause }.any { it is GroupIdNotFoundException } }
                .untilAsserted {
                    apps.assertAlive()
                    val members = admin.describeConsumerGroups(listOf(group)).all().get(3, TimeUnit.SECONDS).getValue(group).members()
                    assertThat(members.flatMap { it.assignment().topicPartitions() }).containsExactlyInAnyOrderElementsOf(partitions)
                }
        }
    }

    private fun awaitConsumerPassed(record: TopicReader.Record): Long? {
        var committed: Long? = null
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                apps.assertAlive()
                committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS)[
                    TopicPartition(topic, record.partition),
                ]?.offset()
                assertThat(committed).isEqualTo(record.offset + 1)
            }
        }
        return committed
    }

    private fun seedBuyerAndProducts() {
        jdbcTemplate.update(
            "INSERT INTO user (created_at, updated_at, display_name, login_id, status) VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'E2E 구매자', ?, 'ACTIVE')",
            BUYER,
        )
        jdbcTemplate.update("INSERT INTO brand (id, created_at, updated_at, name) VALUES (1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'E2E 브랜드')")
        listOf(PRODUCT_A to 1_000, PRODUCT_B to 2_000).forEach { (productId, price) ->
            jdbcTemplate.update(
                "INSERT INTO product (id, created_at, updated_at, brand_id, name, price, status, stock) " +
                    "VALUES (?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, ?, ?, 'ON_SALE', 10)",
                productId,
                "E2E 상품 $productId",
                price,
            )
        }
    }

    private fun chargePoints(amount: Long) {
        check(post("/api/v1/points/charge", mapOf("amount" to amount)).statusCode() == 200) { "포인트 충전 실패" }
    }

    private fun createOrder(vararg items: Pair<Long, Int>): Long {
        val response = post("/api/v1/orders", mapOf("items" to items.map { (productId, quantity) -> mapOf("productId" to productId, "quantity" to quantity) }))
        check(response.statusCode() == 201) { "주문 생성 실패: ${response.body()}" }
        return objectMapper.readTree(response.body()).path("data").path("id").asLong()
    }

    private fun confirm(orderId: Long) {
        val response = post("/api/v1/orders/$orderId/confirm")
        check(response.statusCode() == 200) { "주문 확정 실패: ${response.body()}" }
    }

    private fun snapshot(orderId: Long) = Snapshot(
        orderStatus = jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String::class.java, orderId)!!,
        stocks = jdbcTemplate.query("SELECT id, stock FROM product WHERE id IN (?, ?)", { row, _ -> row.getLong("id") to row.getInt("stock") }, PRODUCT_A, PRODUCT_B)
            .toMap(),
        pointBalance = jdbcTemplate.queryForObject(
            "SELECT p.balance FROM point p JOIN user u ON u.id = p.user_id WHERE u.login_id = ?",
            Long::class.java,
            BUYER,
        )!!,
        outbox = outboxEventJpaRepository.findAll().filter { it.aggregateId == orderId.toString() }
            .map { OutboxRow(it.eventId, it.status, it.publishedAt?.toInstant(), it.claimedBy) },
        purchaseActivity = jdbcTemplate.query(
            "SELECT product_id, quantity FROM purchase_activity WHERE order_id = ? ORDER BY product_id",
            { row, _ -> row.getLong("product_id") to row.getInt("quantity") },
            orderId,
        ),
    )

    private fun get(path: String): HttpResponse<String> =
        http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: Any? = null): HttpResponse<String> = http.send(
        request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body?.let(objectMapper::writeValueAsString) ?: "")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun request(path: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$apiPort$path")).header("X-USER-ID", BUYER)

    private fun saveEvidence() {
        Files.createDirectories(evidenceDirectory)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidenceDirectory.resolve("e2e-delivery.json").toFile(), run)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @TestConfiguration(proxyBeanMethods = false)
    class TopicConfig {
        @Bean
        fun e2eTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
