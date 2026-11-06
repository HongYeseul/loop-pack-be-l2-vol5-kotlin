package com.loopers.order.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.UserFixture
import com.loopers.order.infrastructure.OrderJpaRepository
import com.loopers.outbox.domain.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.point.domain.PointService
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.utils.DatabaseCleanUp
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
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.kafka.admin.auto-create=true"],
)
@Import(OrderConfirmedOutboxTest.TopicConfig::class)
@Testcontainers
@DisplayName("주문 확정 API 의 outbox 기록")
class OrderConfirmedOutboxTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val userJpaRepository: UserJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val pointService: PointService,
    private val objectMapper: ObjectMapper,
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
            registry.add("commerce.kafka.order-topic") { topic }
        }
    }

    private lateinit var consumer: KafkaConsumer<String, ByteArray>
    private var productId = 0L

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

        val userId = userJpaRepository.save(UserFixture.user()).userId
        val brandId = brandJpaRepository.save(BrandFixture.brand()).brandId
        productId = productJpaRepository.save(ProductFixture.product(brandId = brandId, price = 1000L, stock = 10)).productId
        pointService.charge(userId, 20_000L)
    }

    @AfterEach
    fun tearDown() {
        consumer.close(Duration.ofSeconds(5))
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("확정해도 Kafka 에는 아무것도 나가지 않고, 설정된 토픽을 담은 outbox 행이 PENDING 으로 남는다")
    fun leavesOutboxRowWithoutPublishing() {
        val orderId = createOrder()

        assertThat(post("/api/v1/orders/$orderId/confirm").statusCode).isEqualTo(HttpStatus.OK)

        assertThat(consumer.poll(Duration.ofMillis(500))).isEmpty()
        val row = outboxEventJpaRepository.findAll().single()
        assertThat(row.topic).isEqualTo(topic)
        assertThat(row.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(row.publishedAt).isNull()
    }

    @Test
    @DisplayName("outbox 본문이 저장된 주문 확정 사실과 일치한다")
    fun storesConfirmedOrderAsPayload() {
        val orderId = createOrder()
        post("/api/v1/orders/$orderId/confirm")

        val event = objectMapper.readTree(outboxEventJpaRepository.findAll().single().payload)
        val storedOrder = orderJpaRepository.findById(orderId).get()

        assertThat(event.path("eventId").asText()).isEqualTo("order-confirmed:v1:$orderId")
        assertThat(event.path("orderId").asLong()).isEqualTo(orderId)
        assertThat(Instant.parse(event.path("confirmedAt").asText())).isEqualTo(storedOrder.confirmedAt!!.toInstant())
        assertThat(event.path("items")).hasSize(1)
        assertThat(event.path("items").first().path("productId").asLong()).isEqualTo(productId)
        assertThat(event.path("items").first().path("quantity").asInt()).isEqualTo(2)
    }

    private fun createOrder(): Long {
        val response = post(
            "/api/v1/orders",
            mapOf("items" to listOf(mapOf("productId" to productId, "quantity" to 2))),
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
    class TopicConfig {
        @Bean
        fun outboxTopic(): NewTopic = NewTopic(topic, 2, 1)
    }
}
