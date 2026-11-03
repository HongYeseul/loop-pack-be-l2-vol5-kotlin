package com.loopers.order.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.order.domain.OrderItemRepository
import com.loopers.order.domain.OrderStatus
import com.loopers.order.domain.event.OrderConfirmed
import com.loopers.order.infrastructure.OrderJpaRepository
import com.loopers.point.domain.PointService
import com.loopers.point.infrastructure.PointJpaRepository
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.GroupIdNotFoundException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["commerce.kafka.order-confirmed-enabled=true", "spring.kafka.admin.auto-create=true"],
)
@Import(OrderDeliveryReproductionTest.ReproductionConfig::class)
@Testcontainers
@DisplayName("실제 주문 API와 별도 구매 활동 consumer의 발행 경계 재현")
class OrderDeliveryReproductionTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val orders: OrderJpaRepository,
    private val orderItems: OrderItemRepository,
    private val products: ProductJpaRepository,
    private val users: UserJpaRepository,
    private val brands: BrandJpaRepository,
    private val pointService: PointService,
    private val points: PointJpaRepository,
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    private val environment: Environment,
) {
    companion object {
        private val topic = "order-delivery-${UUID.randomUUID()}"
        private val now = Instant.parse("2026-10-30T01:02:03.123456789Z")

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @AfterAll
        @JvmStatic
        fun cleanCaseData(@Autowired jdbc: JdbcTemplate, @Autowired cleanUp: DatabaseCleanUp) {
            // 같은 토픽의 다른 사례와 주문 ID가 겹치지 않도록 클래스 종료 시 정리
            jdbc.execute("DROP TABLE IF EXISTS purchase_activity")
            cleanUp.truncateAllTables()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("commerce.kafka.order-topic") { topic }
        }
    }

    @SpykBean
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    private val group = "purchase-delivery-${UUID.randomUUID()}"
    private var streamer: Process? = null
    private val loginId = "user${UUID.randomUUID().toString().take(8)}"
    private var userId = 0L
    private var firstProduct = 0L
    private var secondProduct = 0L
    private val partitions get() = listOf(TopicPartition(topic, 0), TopicPartition(topic, 1))
    private val evidenceDirectory: Path = Path.of(System.getProperty("week6.evidence-directory"))

    @AfterEach
    fun tearDown() {
        every { kafkaTemplate.send(topic, any<Any>(), any<Any>()) } answers { callOriginal() }
        streamer?.let {
            it.destroy()
            if (!it.waitFor(10, TimeUnit.SECONDS)) {
                it.destroyForcibly()
                check(it.waitFor(5, TimeUnit.SECONDS)) { "실험용 streamer 종료 실패" }
            }
        }
    }

    @BeforeEach
    fun prepareOrderAndConsumer() {
        every { kafkaTemplate.send(topic, any<Any>(), any<Any>()) } answers { callOriginal() }
        userId = users.save(UserFixture.user(loginId = loginId)).userId
        val brandId = brands.save(BrandFixture.brand()).brandId
        firstProduct = products.save(ProductFixture.product(brandId = brandId, price = 1_000, stock = 10)).productId
        secondProduct = products.save(ProductFixture.product(brandId = brandId, price = 2_000, stock = 10)).productId
        pointService.charge(userId, 20_000)
        prepareGroupOffsets()
        startStreamer()
        awaitConsumerAssignment()
    }

    @Test
    @DisplayName("커밋 후 실제 send 전에 실패하면 확정 주문은 남고 브로커 기록과 구매 활동은 생기지 않는다")
    fun leavesCommittedOrderWithoutPublishedEvent() {
        Files.deleteIfExists(evidenceDirectory.resolve("order-unpublished.json"))
        val orderId = createPurchaseOrder()
        var committedAtBlockedSend = false
        every { kafkaTemplate.send(topic, orderId.toString(), any<Any>()) } answers {
            committedAtBlockedSend = committedStatus(orderId) == "CONFIRMED"
            throw KafkaException("실험: DB 커밋 후 실제 Kafka send 진입 전 중단")
        }
        observer().use { consumer ->
            val before = consumer.endOffsets(partitions)

            val response = post("/api/v1/orders/$orderId/confirm")

            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(committedAtBlockedSend).isTrue()
            assertThat(consumer.endOffsets(partitions)).isEqualTo(before)
            assertThat(orders.findById(orderId).get().status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(products.findById(firstProduct).get().stock).isEqualTo(8)
            assertThat(products.findById(secondProduct).get().stock).isEqualTo(9)
            assertThat(points.findByUserId(userId)!!.balance).isEqualTo(16_000)
            assertThat(activity(orderId)).isEmpty()
            assertThat(missingOrders()).containsExactly(orderId)
            saveEvidence(
                "order-unpublished",
                orderId,
                mapOf(
                    "hook" to "AFTER_COMMIT, KafkaTemplate 실제 send 호출 전 예외",
                    "committedOrderVisibleAtHook" to committedAtBlockedSend,
                    "brokerEndOffsetsBefore" to offsets(before),
                    "brokerEndOffsetsAfter" to offsets(consumer.endOffsets(partitions)),
                    "missingConfirmedOrderIds" to missingOrders(),
                ),
            )
        }
    }

    @Test
    @DisplayName("정상 확정 주문의 이벤트를 실제 consumer가 전체 구매 활동으로 저장하고 ack한다")
    fun storesPurchaseActivityFromPublishedOrder() {
        Files.deleteIfExists(evidenceDirectory.resolve("order-consumed.json"))
        val orderId = createPurchaseOrder()
        observer().use { consumer ->
            val before = consumer.endOffsets(partitions)

            val response = post("/api/v1/orders/$orderId/confirm")

            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val record = awaitRecord(consumer)
            val after = consumer.endOffsets(partitions)
            assertThat(after.values.sum() - before.values.sum()).isEqualTo(1)
            assertThat(partitions.all { consumer.position(it) >= after.getValue(it) }).isTrue()
            assertThat(record.key()).isEqualTo(orderId.toString())
            val expected = event(orderId)
            assertThat(mapper.readTree(record.value())).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(expected)))
            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                assertStreamerAlive()
                assertThat(activity(orderId)).containsExactly(
                    mapOf("productId" to firstProduct, "quantity" to 2, "confirmedAt" to expected.confirmedAt.toString()),
                    mapOf("productId" to secondProduct, "quantity" to 1, "confirmedAt" to expected.confirmedAt.toString()),
                )
                assertThat(committed(record)).isEqualTo(record.offset() + 1)
            }
            assertThat(orders.findById(orderId).get().status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(products.findById(firstProduct).get().stock).isEqualTo(8)
            assertThat(products.findById(secondProduct).get().stock).isEqualTo(9)
            assertThat(points.findByUserId(userId)!!.balance).isEqualTo(16_000)
            assertThat(missingOrders()).isEmpty()
            saveEvidence(
                "order-consumed",
                orderId,
                mapOf(
                    "brokerRecord" to mapOf("topic" to record.topic(), "partition" to record.partition(), "offset" to record.offset()),
                    "committedOffset" to committed(record),
                ),
            )
        }
    }

    private fun prepareGroupOffsets() {
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(5, TimeUnit.SECONDS)
            admin.alterConsumerGroupOffsets(group, ends.mapValues { OffsetAndMetadata(it.value.offset()) }).all().get(5, TimeUnit.SECONDS)
        }
    }

    private fun awaitConsumerAssignment() {
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .ignoreExceptionsMatching { error -> generateSequence(error) { it.cause }.any { it is GroupIdNotFoundException } }
                .untilAsserted {
                    assertStreamerAlive()
                    val members = admin.describeConsumerGroups(listOf(group)).all().get(3, TimeUnit.SECONDS).getValue(group).members()
                    assertThat(members).hasSize(1)
                    assertThat(members.single().assignment().topicPartitions()).containsExactlyInAnyOrderElementsOf(partitions)
                }
        }
    }

    private fun awaitRecord(consumer: KafkaConsumer<String, ByteArray>): ConsumerRecord<String, ByteArray> {
        val records = mutableListOf<ConsumerRecord<String, ByteArray>>()
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            records += consumer.poll(Duration.ofMillis(200))
            assertThat(records).hasSize(1)
        }
        return records.single()
    }

    private fun committed(record: ConsumerRecord<String, ByteArray>): Long? =
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS)[TopicPartition(topic, record.partition())]?.offset()
        }

    private fun committedStatus(orderId: Long): String? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status FROM orders WHERE id = ?").use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private fun missingOrders(): List<Long> = jdbc.queryForList(
        "SELECT o.id FROM orders o WHERE o.user_id = ? AND o.status = 'CONFIRMED' " +
            "AND NOT EXISTS (SELECT 1 FROM purchase_activity p WHERE p.order_id = o.id) ORDER BY o.id",
        Long::class.java,
        userId,
    )

    private fun saveEvidence(name: String, orderId: Long, details: Map<String, Any?>) {
        Files.createDirectories(evidenceDirectory)
        mapper.writerWithDefaultPrettyPrinter().writeValue(
            evidenceDirectory.resolve("$name.json").toFile(),
            mapOf(
                "sourceRevision" to System.getProperty("week6.source-revision"),
                "observedAt" to Instant.now().toString(),
                "topic" to topic,
                "group" to group,
                "clock" to now.toString(),
                "initialPointBalance" to 20_000,
                "order" to snapshot(orderId, userId, listOf(firstProduct, secondProduct)),
                "providerOrderId" to "해당 없음: 현재 포인트 결제 경로",
                "transactionKey" to "해당 없음: 현재 포인트 결제 경로",
            ) + details,
        )
    }

    private fun startStreamer() {
        Files.createDirectories(evidenceDirectory)
        val arguments = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m",
            "-jar",
            System.getProperty("week6.streamer-jar"),
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none",
            "--spring.jpa.hibernate.ddl-auto=update",
            "--spring.jpa.show-sql=false",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.kafka.consumer.group-id=$group",
            "--spring.kafka.listener.auto-startup=true",
            "--commerce.kafka.order-topic=$topic",
            "--datasource.mysql-jpa.main.jdbc-url=${environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url")}",
            "--datasource.mysql-jpa.main.username=${environment.getRequiredProperty("datasource.mysql-jpa.main.username")}",
            "--datasource.mysql-jpa.main.password=${environment.getRequiredProperty("datasource.mysql-jpa.main.password")}",
            "--datasource.mysql-jpa.main.maximum-pool-size=3",
            "--datasource.mysql-jpa.main.minimum-idle=1",
        )
        streamer = ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(evidenceDirectory.resolve("streamer-$group.log").toFile())
            .start()
    }

    private fun assertStreamerAlive() {
        check(streamer?.isAlive == true) {
            "실험용 streamer 종료: ${Files.readString(evidenceDirectory.resolve("streamer-$group.log")).takeLast(8_000)}"
        }
    }

    private fun event(orderId: Long): OrderConfirmed = OrderConfirmed.from(
        orders.findById(orderId).get(),
        orderItems.findAllByOrderId(orderId),
    )

    private fun activity(orderId: Long): List<Map<String, Any>> = jdbc.query(
        "SELECT product_id, quantity, confirmed_at FROM purchase_activity WHERE order_id = ? ORDER BY product_id",
        { row, _ ->
            mapOf(
                "productId" to row.getLong("product_id"),
                "quantity" to row.getInt("quantity"),
                "confirmedAt" to row.getTimestamp("confirmed_at").toLocalDateTime().toInstant(ZoneOffset.UTC).toString(),
            )
        },
        orderId,
    )

    private fun snapshot(orderId: Long, userId: Long, productIds: List<Long>): Map<String, Any?> {
        val order = orders.findById(orderId).get()
        return mapOf(
            "event" to event(orderId),
            "orderStatus" to order.status,
            "paidAmount" to order.paidAmount,
            "pointBalance" to points.findByUserId(userId)!!.balance,
            "stocks" to productIds.map { mapOf("productId" to it, "stock" to products.findById(it).get().stock) },
            "purchaseActivity" to activity(orderId),
        )
    }

    private fun offsets(values: Map<TopicPartition, Long>): Map<String, Long> =
        values.entries.sortedBy { it.key.partition() }.associate { it.key.partition().toString() to it.value }

    private fun observer(): KafkaConsumer<String, ByteArray> = KafkaConsumer<String, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
    ).apply {
        assign(partitions)
        seekToEnd(partitions)
        partitions.forEach { position(it, Duration.ofSeconds(5)) }
    }

    private fun createPurchaseOrder(): Long {
        val items = listOf(
            mapOf("productId" to firstProduct, "quantity" to 2),
            mapOf("productId" to secondProduct, "quantity" to 1),
        )
        val response = post("/api/v1/orders", mapOf("items" to items))
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return requireNotNull(response.body).path("data").path("id").asLong()
    }

    private fun post(path: String, body: Any? = null) = restTemplate.exchange(
        path,
        HttpMethod.POST,
        HttpEntity(
            body,
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-USER-ID", loginId)
            },
        ),
        JsonNode::class.java,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class ReproductionConfig {
        @Bean
        fun reproductionTopic(): NewTopic = NewTopic(topic, 2, 1)

        @Primary
        @Bean
        fun reproductionClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }
}
