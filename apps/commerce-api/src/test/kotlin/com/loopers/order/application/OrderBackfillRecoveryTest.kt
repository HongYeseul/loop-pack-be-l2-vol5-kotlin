package com.loopers.order.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.order.domain.OrderItemRepository
import com.loopers.order.domain.event.OrderConfirmed
import com.loopers.order.infrastructure.OrderJpaRepository
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.point.domain.PointService
import com.loopers.point.infrastructure.PointJpaRepository
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.utils.AppProcesses
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.TopicReader
import com.ninjasquad.springmockk.SpykBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * outbox 도입 전(직접 발행)에 확정돼 이벤트가 빠진 주문을 commerce-batch 로 outbox 에 채우고, relay 와 실제 consumer 로 복구하는 한 번의 실행.
 * 6주차 코드로 만든 jar 대신, 확정할 때 outbox 를 쓰지 않게 해 직접 발행 시절의 확정을 흉내 냄 (outbox 행을 지우거나 상태를 직접 넣지 않음)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.kafka.admin.auto-create=true"])
@Import(OrderBackfillRecoveryTest.TopicConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("outbox 도입 전에 빠진 주문 확정 이벤트를 batch 로 채워 복구하는 한 번의 실행")
class OrderBackfillRecoveryTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val orders: OrderJpaRepository,
    private val orderItems: OrderItemRepository,
    private val products: ProductJpaRepository,
    private val users: UserJpaRepository,
    private val brands: BrandJpaRepository,
    private val pointService: PointService,
    private val points: PointJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val environment: Environment,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val topic = "order-backfill-${UUID.randomUUID()}"

        // 테스트 인스턴스를 클래스당 하나 쓰면 Spring 컨텍스트가 @Container 시작보다 먼저 떠서 직접 시작
        private val kafka = KafkaTestFixture().apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("commerce.kafka.order-topic") { topic }
        }
    }

    @SpykBean
    private lateinit var orderConfirmedOutbox: OrderConfirmedOutbox

    private val group = "purchase-backfill-${UUID.randomUUID()}"
    private val loginId = "backfill${UUID.randomUUID().toString().take(8)}"
    private var userId = 0L
    private var firstProduct = 0L
    private var secondProduct = 0L
    private lateinit var apps: AppProcesses
    private lateinit var run: Run

    data class Snapshot(
        val outbox: Map<Long, OutboxStatus>,
        val purchaseActivity: Map<Long, List<Pair<Long, Int>>>,
        val orderStatuses: Map<Long, String>,
        val stocks: Map<Long, Int>,
        val pointBalance: Long,
    )

    data class Run(
        val lost: Long,
        val consumed: Long,
        val beforeRecovery: Snapshot,
        val firstBackfillExitCode: Int,
        val afterFirstBackfill: Snapshot,
        val secondBackfillExitCode: Int,
        val afterSecondBackfill: Snapshot,
        val brokerEventIds: List<String?>,
        val atEnd: Snapshot,
    )

    @BeforeAll
    fun runOnce() {
        apps = AppProcesses(Path.of(System.getProperty("week6.evidence-directory")).resolve("backfill"), commonAppOptions())
        prepareBuyerAndProducts()
        startConsumer()

        TopicReader(kafka.bootstrapServers, topic).use { broker ->
            // 1. 직접 발행 시절처럼 확정 트랜잭션이 outbox 를 쓰지 않는 상태에서 두 주문을 확정
            every { orderConfirmedOutbox.append(any()) } just Runs
            val lost = createAndConfirmOrder()
            val consumed = createAndConfirmOrder()

            // 2. 직접 발행: consumed 만 보내 소비까지 끝냄. lost 는 커밋 후 전송 전에 멈춘 것과 같은 상태
            kafkaTemplate.send(topic, consumed.toString(), event(consumed)).get(10, TimeUnit.SECONDS)
            awaitPurchaseActivity(consumed)
            val beforeRecovery = snapshot(lost, consumed)

            // 3. batch 로 두 주문의 outbox 를 채우고, 같은 대상으로 한 번 더 돌림
            val firstExit = runBackfill(lost, consumed)
            val afterFirst = snapshot(lost, consumed)
            val secondExit = runBackfill(lost, consumed)
            val afterSecond = snapshot(lost, consumed)

            // 4. relay 를 띄워 채운 행을 보내고, consumer 가 빠졌던 주문을 처리할 때까지 기다림
            startRelay()
            awaitPurchaseActivity(lost)
            awaitAllPublished(lost, consumed)
            val brokerEventIds = broker.read(count = 3).map { it.value.let(mapper::readTree).path("eventId").asText() }

            run = Run(lost, consumed, beforeRecovery, firstExit, afterFirst, secondExit, afterSecond, brokerEventIds, snapshot(lost, consumed))
        }
    }

    @AfterAll
    fun tearDown() {
        apps.close()
        // 같은 DB 를 쓰는 다른 테스트 클래스와 섞이지 않도록 streamer 가 만든 테이블까지 정리
        jdbc.execute("DROP TABLE IF EXISTS purchase_activity")
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("직접 발행 시절처럼 확정하면 두 주문 모두 outbox 가 없고, 보내지 못한 주문만 구매 활동이 없다")
    fun startsFromLostState() {
        assertThat(run.beforeRecovery.outbox).isEmpty()
        assertThat(run.beforeRecovery.purchaseActivity.getValue(run.lost)).isEmpty()
        assertThat(run.beforeRecovery.purchaseActivity.getValue(run.consumed)).isNotEmpty()
    }

    @Test
    @DisplayName("batch 는 두 주문의 outbox 를 PENDING 으로 하나씩 채우고 성공으로 끝난다")
    fun backfillAddsPendingRows() {
        assertThat(run.firstBackfillExitCode).isZero()
        assertThat(run.afterFirstBackfill.outbox).isEqualTo(mapOf(run.lost to OutboxStatus.PENDING, run.consumed to OutboxStatus.PENDING))
    }

    @Test
    @DisplayName("같은 대상으로 batch 를 다시 돌려도 성공으로 끝나고 outbox 는 늘지 않는다")
    fun backfillIsRepeatable() {
        assertThat(run.secondBackfillExitCode).isZero()
        assertThat(run.afterSecondBackfill.outbox).isEqualTo(run.afterFirstBackfill.outbox)
    }

    @Test
    @DisplayName("relay 가 보내면 빠졌던 주문의 구매 활동이 상품별로 생긴다")
    fun recoversLostOrder() {
        assertThat(run.atEnd.purchaseActivity.getValue(run.lost)).containsExactly(firstProduct to 2, secondProduct to 1)
    }

    @Test
    @DisplayName("이미 소비된 주문은 복구로 다시 받아도 구매 활동이 그대로다")
    fun keepsConsumedOrderUnchanged() {
        assertThat(run.atEnd.purchaseActivity.getValue(run.consumed)).isEqualTo(run.beforeRecovery.purchaseActivity.getValue(run.consumed))
    }

    @Test
    @DisplayName("브로커에는 빠졌던 주문이 한 번, 이미 소비된 주문이 두 번(직접 발행과 복구) 남는다")
    fun brokerHasLostOnceAndConsumedTwice() {
        assertThat(run.brokerEventIds.groupingBy { it }.eachCount()).isEqualTo(
            mapOf("order-confirmed:v1:${run.lost}" to 1, "order-confirmed:v1:${run.consumed}" to 2),
        )
    }

    @Test
    @DisplayName("복구하는 동안 주문 상태, 재고, 포인트는 바뀌지 않는다")
    fun recoveryDoesNotChangeOrderStockOrPoints() {
        assertThat(run.atEnd.copy(outbox = emptyMap(), purchaseActivity = emptyMap()))
            .isEqualTo(run.beforeRecovery.copy(outbox = emptyMap(), purchaseActivity = emptyMap()))
    }

    // ---- 준비

    private fun prepareBuyerAndProducts() {
        userId = users.save(UserFixture.user(loginId = loginId)).userId
        val brandId = brands.save(BrandFixture.brand()).brandId
        firstProduct = products.save(ProductFixture.product(brandId = brandId, price = 1_000, stock = 10)).productId
        secondProduct = products.save(ProductFixture.product(brandId = brandId, price = 2_000, stock = 10)).productId
        pointService.charge(userId, 20_000)
    }

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

    private fun startConsumer() {
        val partitions = listOf(TopicPartition(topic, 0), TopicPartition(topic, 1))
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(5, TimeUnit.SECONDS)
            admin.alterConsumerGroupOffsets(group, ends.mapValues { OffsetAndMetadata(it.value.offset()) }).all().get(5, TimeUnit.SECONDS)
            apps.start(
                "streamer",
                System.getProperty("week6.streamer-jar"),
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

    private fun startRelay() {
        apps.start(
            "relay",
            System.getProperty("week7.relay-jar"),
            "--outbox.relay.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate",
        )
    }

    private fun runBackfill(vararg orderIds: Long): Int = apps.run(
        "batch-${System.nanoTime()}",
        System.getProperty("week7.batch-jar"),
        "--spring.batch.job.name=backfillOrderConfirmedOutboxJob",
        // 이 테스트의 API 가 만든 스키마를 batch 가 지우고 다시 만들지 않도록
        "--spring.jpa.hibernate.ddl-auto=none",
        "orderIds=${orderIds.joinToString(";")}",
    )

    // ---- 주문과 관찰

    private fun createAndConfirmOrder(): Long {
        val items = listOf(mapOf("productId" to firstProduct, "quantity" to 2), mapOf("productId" to secondProduct, "quantity" to 1))
        val orderId = requireNotNull(post("/api/v1/orders", mapOf("items" to items)).body).path("data").path("id").asLong()
        check(post("/api/v1/orders/$orderId/confirm").statusCode.is2xxSuccessful) { "주문 확정 실패: $orderId" }
        return orderId
    }

    private fun event(orderId: Long): OrderConfirmed =
        OrderConfirmed.from(orders.findById(orderId).get(), orderItems.findAllByOrderId(orderId))

    private fun awaitPurchaseActivity(orderId: Long) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted {
            apps.assertAlive()
            assertThat(purchaseActivity(orderId)).isNotEmpty()
        }
    }

    private fun awaitAllPublished(vararg orderIds: Long) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertThat(outbox(*orderIds).values).containsOnly(OutboxStatus.PUBLISHED)
        }
    }

    private fun snapshot(vararg orderIds: Long) = Snapshot(
        outbox = outbox(*orderIds),
        purchaseActivity = orderIds.associateWith { purchaseActivity(it) },
        orderStatuses = orderIds.associateWith { orders.findById(it).get().status.name },
        stocks = listOf(firstProduct, secondProduct).associateWith { products.findById(it).get().stock },
        pointBalance = points.findByUserId(userId)!!.balance,
    )

    private fun outbox(vararg orderIds: Long): Map<Long, OutboxStatus> = outboxEventJpaRepository.findAll()
        .filter { it.aggregateId.toLong() in orderIds }
        .associate { it.aggregateId.toLong() to it.status }

    private fun purchaseActivity(orderId: Long): List<Pair<Long, Int>> = jdbc.query(
        "SELECT product_id, quantity FROM purchase_activity WHERE order_id = ? ORDER BY product_id",
        { row, _ -> row.getLong("product_id") to row.getInt("quantity") },
        orderId,
    )

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
    class TopicConfig {
        @Bean
        fun backfillTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
