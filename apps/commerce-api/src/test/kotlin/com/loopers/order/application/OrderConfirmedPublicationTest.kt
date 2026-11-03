package com.loopers.order.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.order.domain.OrderStatus
import com.loopers.order.infrastructure.OrderJpaRepository
import com.loopers.order.infrastructure.OrderKafkaEventPublisher
import com.loopers.point.domain.PointService
import com.loopers.point.infrastructure.PointJpaRepository
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.user.domain.LoginId
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import io.mockk.verify
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["commerce.kafka.order-confirmed-enabled=true", "spring.kafka.admin.auto-create=true"],
)
@Import(OrderConfirmedPublicationTest.PublicationConfig::class)
@Testcontainers
@DisplayName("주문 확정 후 Kafka 발행과 실패 처리")
class OrderConfirmedPublicationTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val orderFacade: OrderFacade,
    private val orderJpaRepository: OrderJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val pointService: PointService,
    private val pointJpaRepository: PointJpaRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private val topic = "order-events-${UUID.randomUUID()}"
        private val now = Instant.parse("2026-10-27T01:02:03.123456789Z")

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("commerce.kafka.order-topic") { topic }
        }
    }

    @SpykBean
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    private lateinit var consumer: KafkaConsumer<String, ByteArray>
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val publisherLogger = LoggerFactory.getLogger(OrderKafkaEventPublisher::class.java) as Logger
    private var userId = 0L
    private var productId = 0L

    @BeforeEach
    fun setUp() {
        every { kafkaTemplate.send(topic, any<Any>(), any<Any>()) } answers { callOriginal() }
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
        appender = ListAppender<ILoggingEvent>().apply { start() }
        publisherLogger.addAppender(appender)

        userId = userJpaRepository.save(UserFixture.user()).userId
        val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        productId = productJpaRepository.save(ProductFixture.product(brandId = brandId, price = 1000L, stock = 10)).productId
        pointService.charge(userId, 20_000L)
    }

    @AfterEach
    fun tearDown() {
        consumer.close(Duration.ofSeconds(5))
        publisherLogger.detachAppender(appender)
        appender.stop()
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("주문 확정 DB 커밋이 다른 연결에 보인 뒤 Kafka로 발행한다")
    fun publishesAfterCommit() {
        val orderId = createOrder()
        val statusAtSend = AtomicReference<String>()
        every { kafkaTemplate.send(topic, any<Any>(), any<Any>()) } answers {
            dataSource.connection.use { connection ->
                connection.prepareStatement("select status from orders where id = ?").use { statement ->
                    statement.setLong(1, orderId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        statusAtSend.set(rows.getString(1))
                    }
                }
            }
            callOriginal()
        }

        val response = post("/api/v1/orders/$orderId/confirm")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(statusAtSend.get()).isEqualTo("CONFIRMED")
        assertThat(consumer.poll(Duration.ofSeconds(15))).hasSize(1)
        assertThat(productJpaRepository.findById(productId).get().stock).isEqualTo(8)
        verify(exactly = 1) { kafkaTemplate.send(topic, orderId.toString(), any<Any>()) }
    }

    @Test
    @DisplayName("발행된 key와 payload가 저장된 주문 확정 사실과 일치한다")
    fun publishesStoredPurchaseSnapshot() {
        val orderId = createOrder()
        post("/api/v1/orders/$orderId/confirm")

        val record = consumer.poll(Duration.ofSeconds(15)).single()
        val event = objectMapper.readTree(record.value())
        val storedOrder = orderJpaRepository.findById(orderId).get()

        assertThat(record.key()).isEqualTo(orderId.toString())
        assertThat(event.path("eventId").asText()).isEqualTo("order-confirmed:v1:$orderId")
        assertThat(event.path("eventType").asText()).isEqualTo("OrderConfirmed")
        assertThat(event.path("schemaVersion").asInt()).isEqualTo(1)
        assertThat(event.path("orderId").asLong()).isEqualTo(orderId)
        assertThat(Instant.parse(event.path("confirmedAt").asText())).isEqualTo(storedOrder.confirmedAt!!.toInstant())
        assertThat(event.path("items")).hasSize(1)
        assertThat(event.path("items").first().path("productId").asLong()).isEqualTo(productId)
        assertThat(event.path("items").first().path("quantity").asInt()).isEqualTo(2)
    }

    @Test
    @DisplayName("주문 확정 트랜잭션이 아직 커밋되지 않았으면 발행하지 않는다")
    fun doesNotPublishBeforeCommit() {
        val orderId = createOrder()

        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            orderFacade.confirm(LoginId(UserFixture.DEFAULT_LOGIN_ID), orderId)

            verify(exactly = 0) { kafkaTemplate.send(topic, any<Any>(), any<Any>()) }
            assertThat(consumer.poll(Duration.ofMillis(300))).isEmpty()
            status.setRollbackOnly()
        }
    }

    @Test
    @DisplayName("주문 확정 트랜잭션이 롤백되면 이벤트를 발행하지 않는다")
    fun doesNotPublishAfterRollback() {
        val orderId = createOrder()

        assertThrows<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                orderFacade.confirm(LoginId(UserFixture.DEFAULT_LOGIN_ID), orderId)
                throw IllegalStateException("확정 후 rollback 주입")
            }
        }

        verify(exactly = 0) { kafkaTemplate.send(topic, any<Any>(), any<Any>()) }
        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
        assertThat(orderJpaRepository.findById(orderId).get().status).isEqualTo(OrderStatus.DRAFT)
        assertThat(productJpaRepository.findById(productId).get().stock).isEqualTo(10)
        assertThat(pointJpaRepository.findByUserId(userId)!!.balance).isEqualTo(20_000L)
    }

    @Test
    @DisplayName("같은 주문을 다시 확정해도 이벤트를 추가로 발행하지 않는다")
    fun rejectsRepeatedConfirmationWithoutAnotherSend() {
        val orderId = createOrder()
        assertThat(post("/api/v1/orders/$orderId/confirm").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(consumer.poll(Duration.ofSeconds(15))).hasSize(1)

        assertThat(post("/api/v1/orders/$orderId/confirm").statusCode).isEqualTo(HttpStatus.CONFLICT)

        verify(exactly = 1) { kafkaTemplate.send(topic, orderId.toString(), any<Any>()) }
        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
        assertThat(productJpaRepository.findById(productId).get().stock).isEqualTo(8)
    }

    @Test
    @DisplayName("확정이 거부된 주문은 이벤트를 발행하지 않는다")
    fun doesNotPublishRejectedConfirmation() {
        val orderId = createOrder(quantity = 11)

        assertThat(post("/api/v1/orders/$orderId/confirm").statusCode).isEqualTo(HttpStatus.CONFLICT)

        verify(exactly = 0) { kafkaTemplate.send(topic, any<Any>(), any<Any>()) }
        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
        assertThat(orderJpaRepository.findById(orderId).get().status).isEqualTo(OrderStatus.DRAFT)
    }

    @Test
    @DisplayName("동기 발행 오류가 발생해도 확정 결과를 유지하고 실패를 기록한다")
    fun keepsConfirmedOrderWhenSendThrows() {
        every { kafkaTemplate.send(topic, any<Any>(), any<Any>()) } throws KafkaException("전송 실패 주입")
        val orderId = createOrder()

        assertThat(post("/api/v1/orders/$orderId/confirm").statusCode).isEqualTo(HttpStatus.OK)

        assertPublicationFailure(orderId)
    }

    @Test
    @DisplayName("비동기 발행 오류가 발생해도 확정 결과를 유지하고 실패를 기록한다")
    fun keepsConfirmedOrderWhenSendResultFailsLater() {
        val pending = CompletableFuture<SendResult<Any, Any>>()
        every { kafkaTemplate.send(topic, any<Any>(), any<Any>()) } returns pending
        val orderId = createOrder()

        assertThat(post("/api/v1/orders/$orderId/confirm").statusCode).isEqualTo(HttpStatus.OK)
        pending.completeExceptionally(KafkaException("비동기 전송 실패 주입"))

        assertPublicationFailure(orderId)
    }

    private fun assertPublicationFailure(orderId: Long) {
        assertThat(orderJpaRepository.findById(orderId).get().status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(productJpaRepository.findById(productId).get().stock).isEqualTo(8)
        assertThat(pointJpaRepository.findByUserId(userId)!!.balance).isEqualTo(18_000L)
        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
        assertThat(appender.list).anySatisfy {
            assertThat(it.level).isEqualTo(Level.ERROR)
            assertThat(it.formattedMessage).contains("order-confirmed:v1:$orderId", "발행 실패")
        }
    }

    private fun createOrder(quantity: Int = 2): Long {
        val response = post(
            "/api/v1/orders",
            mapOf("items" to listOf(mapOf("productId" to productId, "quantity" to quantity))),
        )
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
                set("X-USER-ID", UserFixture.DEFAULT_LOGIN_ID)
            },
        ),
        JsonNode::class.java,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class PublicationConfig {
        @Bean
        fun publicationTopic(): NewTopic = NewTopic(topic, 2, 1)

        @Primary
        @Bean
        fun publicationClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }
}
