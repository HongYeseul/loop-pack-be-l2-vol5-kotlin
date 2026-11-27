package com.loopers.payment.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.brand.infrastructure.BrandJpaRepository
import com.loopers.coupon.Coupon
import com.loopers.coupon.CouponCampaign
import com.loopers.coupon.infrastructure.CouponCampaignJpaRepository
import com.loopers.coupon.infrastructure.CouponJpaRepository
import com.loopers.fixture.BrandFixture
import com.loopers.fixture.ProductFixture
import com.loopers.fixture.TestPaymentProvider
import com.loopers.fixture.UserFixture
import com.loopers.outbox.OutboxStatus
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.payment.PaymentAttempt
import com.loopers.product.infrastructure.ProductJpaRepository
import com.loopers.testcontainers.KafkaTestFixture
import com.loopers.testcontainers.RedisTestContainer
import com.loopers.user.infrastructure.UserJpaRepository
import com.loopers.utils.AppProcesses
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["management.server.port=0", "spring.jpa.show-sql=false"])
@DirtiesContext
@Import(CheckoutIncidentTest.IncidentConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class CheckoutIncidentTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val mapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val environment: Environment,
    private val users: UserJpaRepository,
    private val brands: BrandJpaRepository,
    private val products: ProductJpaRepository,
    private val campaigns: CouponCampaignJpaRepository,
    private val coupons: CouponJpaRepository,
    private val outbox: OutboxEventJpaRepository,
) {
    companion object {
        private const val BUYER = "incidentbuyer"
        private const val OTHER_BUYER = "incidentother"
        private const val PG_ACCOUNT = "135135"
        private const val AMOUNT = 3_500L
        private const val INITIAL_STOCK = 25
        private const val PG_READ_TIMEOUT_MS = 1_000L
        private const val PERIOD_START = "2026-11-23"
        private const val SNAPSHOT_ID = "weekly-$PERIOD_START-r1"
        private val confirmedTime = Instant.parse("2026-11-26T12:00:00Z")
        private val topic = "checkout-incident-${UUID.randomUUID()}"
        private val provider = TestPaymentProvider()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("datasource.mysql-jpa.main.jdbc-url", mysql::getJdbcUrl)
            registry.add("datasource.mysql-jpa.main.username", mysql::getUsername)
            registry.add("datasource.mysql-jpa.main.password", mysql::getPassword)
            registry.add("datasource.redis.master.host", redis::getHost)
            registry.add("datasource.redis.master.port", redis::getFirstMappedPort)
            registry.add("datasource.redis.replicas[0].host", redis::getHost)
            registry.add("datasource.redis.replicas[0].port", redis::getFirstMappedPort)
            registry.add("datasource.redis.command-timeout") { "5s" }
            registry.add("commerce.kafka.order-topic") { topic }
            registry.add("commerce.pg.base-url") { provider.baseUrl }
            registry.add("commerce.pg.user-id") { PG_ACCOUNT }
            registry.add("commerce.pg.read-timeout") { "${PG_READ_TIMEOUT_MS}ms" }
        }

        private val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0").apply { start() }
        private val redis = RedisTestContainer().apply { start() }

        @JvmStatic
        @AfterAll
        fun stopDependencies() {
            provider.close()
            redis.stop()
            mysql.stop()
        }
    }

    @LocalManagementPort
    private var managementPort = 0

    @Test
    @DisplayName("PG 성공 응답을 잃은 주문을 복구하고 주간 랭킹까지 반영한다")
    fun recoversCheckoutIncident(output: CapturedOutput) {
        val evidenceRoot = Path.of(System.getProperty("week10.evidence-directory"))
        Files.createDirectories(evidenceRoot)
        val evidence = Files.createTempDirectory(evidenceRoot, "run-")
        var phase = "prepare"
        try {
            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                assertThat(health().statusCode).isEqualTo(HttpStatus.OK)
            }
            val buyerId = users.save(UserFixture.user(loginId = BUYER)).userId
            users.save(UserFixture.user(loginId = OTHER_BUYER))
            val brandId = brands.save(BrandFixture.brand()).brandId
            val productId = products.save(ProductFixture.product(brandId = brandId, price = AMOUNT, stock = INITIAL_STOCK)).productId
            val campaign = campaigns.save(CouponCampaign("실습 쿠폰", 1, "실습 주문 확인용", 100))
            coupons.save(Coupon(campaign.id, buyerId, campaign.name, campaign.benefitDescription, campaign.discountAmount, confirmedTime))
            post("/api/v1/points/charge", mapOf("amount" to 10_000L)).expect(HttpStatus.OK)
            val initialBenefits = benefits(buyerId)
            assertThat(initialBenefits.getValue("coupons").size()).isEqualTo(1)

            val baseline = List(10) { draft(productId) }
            val fault = List(10) { draft(productId) }
            val targets = fault.take(3)
            assertThat(baseline + fault).doesNotHaveDuplicates()
            val targetProviderIds = targets.map(PaymentAttempt::providerOrderIdOf)
            provider.rejectDuplicateCreates = true
            provider.delay = Duration.ofSeconds(3)

            phase = "baseline"
            assertThat(provider.successThenDelayOrders).isEmpty()
            baseline.forEach(::confirmNormally)
            val normal = observe(evidence, phase, baseline, fault, baseline, productId, buyerId, output)
            assertState(normal, emptyList(), expectedCreates = 10, expectedStock = 15)
            assertThat(normal.benefits).isEqualTo(initialBenefits)

            phase = "fault"
            provider.successThenDelayOrders.addAll(targetProviderIds)
            val responseWaits = mutableMapOf<Long, Long>()
            fault.forEach { orderId ->
                if (orderId in targets) {
                    val requestedAt = System.nanoTime()
                    val response = start(orderId).expect(HttpStatus.ACCEPTED)
                    responseWaits[orderId] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt)
                    assertThat(response.path("paymentStatus").asText()).isEqualTo("UNKNOWN")
                } else {
                    confirmNormally(orderId)
                }
            }
            val incident = observe(evidence, phase, baseline, fault, fault, productId, buyerId, output)
            assertState(incident, targets, expectedCreates = 20, expectedStock = 5)
            assertThat(incident.benefits).isEqualTo(initialBenefits)
            val diagnosis = targets.map { orderId ->
                val providerId = PaymentAttempt.providerOrderIdOf(orderId)
                val records = incident.logs.filter { providerId in it }
                val elapsedMs = responseWaits.getValue(orderId)
                assertThat(elapsedMs).isGreaterThanOrEqualTo(PG_READ_TIMEOUT_MS)
                assertThat(records).anyMatch {
                    "PG 결제 생성 응답 확인 실패" in it && ("cause=HttpTimeoutException" in it || "cause=CancellationException" in it)
                }
                assertThat(records).noneMatch { "PG 결과 반영" in it }
                mapOf(
                    "orderId" to orderId,
                    "providerOrderId" to providerId,
                    "elapsedMs" to elapsedMs,
                    "observed" to records,
                    "conclusion" to "PG는 성공했고 생성 응답이 시간 내 도착하지 않음",
                )
            }
            save(
                evidence,
                "diagnosis.json",
                mapOf(
                    "hypotheses" to linkedMapOf(
                        "PG 처리 지연" to "권위 조회 PENDING 예상. 대상 거래 모두 SUCCESS.",
                        "PG 생성 응답 유실" to "권위 조회 SUCCESS와 생성 응답 timeout 예상. 대상에서 모두 관찰.",
                        "내부 적용 실패" to "PG 결과 반영 STARTED 및 FAILED 예상. 대상에서 적용 시도 기록 없음.",
                    ),
                    "orders" to diagnosis,
                ),
            )
            val faultFiles = listOf("fault.json", "fault-health.json", "fault-prometheus.txt", "fault-payment.log", "diagnosis.json")
            val hashes = faultFiles.associateWith { sha256(evidence.resolve(it)) }

            phase = "recovery"
            provider.successThenDelayOrders.clear()
            assertThat(provider.successThenDelayOrders).isEmpty()
            assertThat(post("/api/v1/orders/${targets.first()}/payment/reconcile", login = OTHER_BUYER).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            val responses = targets.associateWith { orderId ->
                val resolved = reconcile(orderId).expect(HttpStatus.OK)
                assertThat(resolved.path("paymentStatus").asText()).isEqualTo("SUCCESS")
                assertThat(resolved.path("transactionKey").asText()).isEqualTo(incident.pg.getValue(orderId).single().path("transactionKey").asText())
                resolved
            }
            val recovered = observe(evidence, phase, baseline, fault, fault, productId, buyerId, output)
            assertState(recovered, emptyList(), expectedCreates = 20, expectedStock = 5)
            assertThat(recovered.orders.keys).isEqualTo(incident.orders.keys)
            assertThat(recovered.pg).isEqualTo(incident.pg)
            targetProviderIds.forEach { providerId ->
                val applyLogs = recovered.logs.filter { providerId in it && "PG 결과 반영" in it }
                assertThat(applyLogs).anyMatch { "STARTED" in it && "SUCCESS" in it }
                assertThat(applyLogs).anyMatch { "COMMITTED" in it && "SUCCESS" in it }
            }
            assertThat(recovered.benefits).isEqualTo(initialBenefits)
            targets.forEach { orderId ->
                val result = responses.getValue(orderId)
                assertThat(reconcile(orderId).expect(HttpStatus.OK)).isEqualTo(result)
                callback(orderId, result.path("transactionKey").asText())
                assertThat(orderState(orderId)).isEqualTo(recovered.orders.getValue(orderId))
            }
            assertThat(provider.createCount.get()).isEqualTo(incident.createCalls)
            assertThat(provider.requests.filter { it.method == "POST" }.map { it.userId }).containsOnly(PG_ACCOUNT)
            save(evidence, "reconcile.json", responses)

            phase = "delivery-and-ranking"
            verifyDeliveryAndRanking(baseline + fault, productId, evidence)
            assertThat(faultFiles.associateWith { sha256(evidence.resolve(it)) }).isEqualTo(hashes)
            val createIncrease = provider.createCount.get() - incident.createCalls
            assertThat(createIncrease).isZero()
            save(evidence, "result.json", mapOf("checkedAt" to Instant.now(), "createIncrease" to createIncrease, "preservedFaultSha256" to hashes))
        } catch (failure: Throwable) {
            save(evidence, "failure.json", mapOf("phase" to phase, "at" to Instant.now(), "type" to failure.javaClass.name, "message" to failure.message))
            throw failure
        } finally {
            provider.successThenDelayOrders.clear()
            provider.rejectDuplicateCreates = false
        }
    }

    private fun verifyDeliveryAndRanking(orderIds: List<Long>, productId: Long, evidence: Path) {
        val eventIds = orderIds.map { "order-confirmed:v1:$it" }
        assertThat(outbox.findAll().map { it.eventId }).containsExactlyInAnyOrderElementsOf(eventIds)
        assertThat(outbox.findAll().map { it.status }).containsOnly(OutboxStatus.PENDING)

        KafkaTestFixture().use { kafka ->
            kafka.start()
            AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(listOf(NewTopic(topic, 1, 1))).all().get(10, TimeUnit.SECONDS)
            }
            AppProcesses(evidence, processOptions()).use { apps ->
                val kafkaOption = "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}"
                apps.start(
                    "streamer",
                    System.getProperty("week6.streamer-jar"),
                    "--spring.main.web-application-type=none",
                    kafkaOption,
                    "--spring.jpa.hibernate.ddl-auto=update",
                    "--spring.kafka.listener.auto-startup=true",
                    "--spring.kafka.consumer.group-id=checkout-incident",
                    "--commerce.kafka.order-topic=$topic",
                )
                apps.start(
                    "relay",
                    System.getProperty("week7.relay-jar"),
                    kafkaOption,
                    "--outbox.relay.enabled=true",
                    "--spring.jpa.hibernate.ddl-auto=validate",
                )
                await().atMost(Duration.ofSeconds(90)).until {
                    apps.assertAlive()
                    val exists = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_activity'",
                        Int::class.java,
                    ) == 1
                    exists && jdbc.queryForObject("SELECT COUNT(*) FROM purchase_activity", Int::class.java) == orderIds.size &&
                        outbox.findAll().all { it.status == OutboxStatus.PUBLISHED }
                }

                val activities = jdbc.queryForList(
                    "SELECT order_id, product_id, quantity, confirmed_at FROM purchase_activity ORDER BY order_id, product_id",
                )
                assertThat(activities.map { (it.getValue("order_id") as Number).toLong() }).containsExactlyInAnyOrderElementsOf(orderIds)
                assertThat(activities).allMatch { it["product_id"] == productId && it["quantity"] == 1 }
                val mismatchedTimestamps = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM purchase_activity p JOIN orders o ON p.order_id=o.id WHERE p.confirmed_at <> o.confirmed_at",
                    Int::class.java,
                )
                assertThat(requireNotNull(mismatchedTimestamps)).isZero()
                val published = outbox.findAll()
                assertThat(published.map { it.eventId }).containsExactlyInAnyOrderElementsOf(eventIds)
                assertThat(published).allMatch { it.status == OutboxStatus.PUBLISHED && it.publishedAt != null }
                save(
                    evidence,
                    "delivery.json",
                    mapOf(
                    "outbox" to published.map { mapOf("eventId" to it.eventId, "status" to it.status, "attemptCount" to it.attemptCount, "publishedAt" to it.publishedAt) },
                    "purchaseActivity" to activities,
                ),
                )

                ResourceDatabasePopulator(ClassPathResource("db/weekly-ranking.sql")).execute(requireNotNull(jdbc.dataSource))
                assertThat(
                    apps.run(
                    "batch",
                        System.getProperty("week7.batch-jar"),
                        "--spring.jpa.hibernate.ddl-auto=none",
                    "--spring.batch.job.name=WEEKLY_RANKING_JOB",
                        "--spring.batch.jdbc.initialize-schema=always",
                    "periodStart=$PERIOD_START,java.time.LocalDate",
                        "revision=1,java.lang.Long",
                ),
                ).isZero()
                assertThat(jdbc.queryForList("SELECT STATUS FROM BATCH_JOB_EXECUTION", String::class.java)).containsExactly("COMPLETED")
                val inputs = jdbc.queryForList("SELECT order_id, product_id, quantity, confirmed_at FROM weekly_ranking_input WHERE snapshot_id = ?", SNAPSHOT_ID)
                assertThat(inputs).containsExactlyInAnyOrderElementsOf(activities)
                val ranking = get("/api/v1/rankings/weekly?periodStart=$PERIOD_START").expect(HttpStatus.OK)
                assertThat(ranking.size()).isEqualTo(1)
                val row = ranking.single()
                assertThat(row.path("periodStart").asText()).isEqualTo(PERIOD_START)
                assertThat(row.path("productId").asLong()).isEqualTo(productId)
                assertThat(row.path("score").asLong()).isEqualTo(20)
                assertThat(row.path("rank").asLong()).isEqualTo(1)
                save(evidence, "ranking.json", mapOf("snapshotId" to SNAPSHOT_ID, "inputs" to inputs, "api" to ranking))
            }
        }
    }

    private data class Observation(
        val orders: Map<Long, Map<String, Any>>,
        val pg: Map<Long, JsonNode>,
        val createCalls: Int,
        val stock: Int,
        val benefits: Map<String, JsonNode>,
        val logs: List<String>,
        val metric: Double,
    )

    private fun health() = http.getForEntity("http://127.0.0.1:$managementPort/actuator/health", String::class.java)

    private fun observe(
        evidence: Path,
        phase: String,
        baseline: List<Long>,
        fault: List<Long>,
        observed: List<Long>,
        productId: Long,
        buyerId: Long,
        output: CapturedOutput,
    ): Observation {
        val health = health()
        val prometheus = http.getForEntity("http://127.0.0.1:$managementPort/actuator/prometheus", String::class.java)
        Files.writeString(evidence.resolve("$phase-health.json"), health.body.orEmpty(), CREATE_NEW)
        Files.writeString(evidence.resolve("$phase-prometheus.txt"), prometheus.body.orEmpty(), CREATE_NEW)
        val providerIds = observed.map(PaymentAttempt::providerOrderIdOf)
        val logs = output.all.lineSequence().filter { line ->
            providerIds.any { it in line } && ("PG 결제 생성 응답 확인 실패" in line || "결제 생성 결과 미확인" in line || "PG 결과 반영" in line)
        }.toList()
        Files.write(evidence.resolve("$phase-payment.log"), logs, CREATE_NEW)
        val orders = observed.associateWith(::orderState)
        val pg = observed.associateWith { orderId ->
            val providerId = PaymentAttempt.providerOrderIdOf(orderId)
            val headers = HttpHeaders().apply { set("X-USER-ID", PG_ACCOUNT) }
            val response = http.exchange("${provider.baseUrl}/api/v1/payments?orderId=$providerId", HttpMethod.GET, HttpEntity<Unit>(headers), String::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            mapper.readTree(response.body)
        }
        val stock = products.findById(productId).orElseThrow().stock
        val benefits = benefits(buyerId)
        save(
            evidence,
            "$phase.json",
            linkedMapOf(
            "run" to evidence.fileName.toString(), "phase" to phase, "observedAt" to Instant.now(),
            "baselineOrders" to baseline, "faultOrders" to fault, "orders" to orders,
            "pgAccount" to PG_ACCOUNT, "pg" to pg, "createCalls" to provider.createCount.get(),
            "createCallsByOrder" to observed.associateWith { provider.createCallsFor(PaymentAttempt.providerOrderIdOf(it)) },
            "stock" to stock, "benefits" to benefits, "healthHttpStatus" to health.statusCode.value(),
            "prometheusHttpStatus" to prometheus.statusCode.value(),
        ),
        )
        assertThat(health.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(mapper.readTree(health.body).path("status").asText()).isEqualTo("UP")
        assertThat(prometheus.statusCode).isEqualTo(HttpStatus.OK)
        val sample = prometheus.body.orEmpty().lineSequence()
            .single { it.startsWith("commerce_payment_unknown{") || it.startsWith("commerce_payment_unknown ") }
        return Observation(orders, pg, provider.createCount.get(), stock, benefits, logs, sample.substringAfterLast(' ').toDouble())
    }

    private fun assertState(observation: Observation, unresolved: List<Long>, expectedCreates: Int, expectedStock: Int) {
        assertThat(observation.orders).hasSize(10)
        assertThat(observation.createCalls).isEqualTo(expectedCreates)
        assertThat(provider.transactions()).hasSize(expectedCreates)
        assertThat(observation.stock).isEqualTo(expectedStock)
        assertThat(observation.metric).isEqualTo(unresolved.size.toDouble())
        observation.orders.forEach { (orderId, state) ->
            val providerId = PaymentAttempt.providerOrderIdOf(orderId)
            val pg = observation.pg.getValue(orderId).single()
            assertThat(pg.path("status").asText()).isEqualTo("SUCCESS")
            assertThat(pg.path("orderId").asText()).isEqualTo(providerId)
            assertThat(pg.path("amount").asLong()).isEqualTo(AMOUNT)
            assertThat(state["amount"]).isEqualTo(AMOUNT)
            assertThat(state["total_amount"]).isEqualTo(AMOUNT)
            assertThat(state["attempt_key"]).isEqualTo("incident-$orderId")
            assertThat(state["provider_order_id"]).isEqualTo(providerId)
            assertThat(provider.createCallsFor(providerId)).isEqualTo(1)
            if (orderId in unresolved) {
                assertThat(state["payment_status"]).isEqualTo("UNKNOWN")
                assertThat(state["order_status"]).isEqualTo("PAYMENT_PENDING")
                assertThat(state["reservation_status"]).isEqualTo("RESERVED")
                assertThat(state["transaction_key"]).isNull()
            } else {
                assertThat(state["payment_status"]).isEqualTo("SUCCESS")
                assertThat(state["order_status"]).isEqualTo("CONFIRMED")
                assertThat(state["reservation_status"]).isEqualTo("CONSUMED")
                assertThat(state["transaction_key"]).isEqualTo(pg.path("transactionKey").asText())
                assertThat(state["paid_amount"]).isEqualTo(AMOUNT)
            }
        }
    }

    private fun orderState(orderId: Long): Map<String, Any> = jdbc.queryForMap(
        """
        SELECT p.attempt_key, p.provider_order_id, p.transaction_key, p.amount, p.status AS payment_status,
               p.dispatched_at, p.updated_at, p.completed_at, o.total_amount, o.paid_amount, o.confirmed_at,
               o.status AS order_status, r.status AS reservation_status
        FROM orders o JOIN payment_attempt p ON o.id = p.order_id JOIN stock_reservation r ON r.order_id = o.id
        WHERE o.id = ?
        """.trimIndent(),
        orderId,
    )

    private fun benefits(userId: Long): Map<String, JsonNode> = mapOf(
        "point" to get("/api/v1/points").expect(HttpStatus.OK),
        "pointLedger" to mapper.valueToTree(jdbc.queryForList("SELECT id, type, amount, balance_after, order_id FROM point_transaction WHERE user_id=? ORDER BY id", userId)),
        "coupons" to get("/api/v1/coupons").expect(HttpStatus.OK),
    )

    private fun processOptions(): List<String> = listOf("--spring.profiles.active=test", "--spring.jpa.show-sql=false") +
        listOf("jdbc-url", "username", "password").map {
            val key = "datasource.mysql-jpa.main.$it"
            "--$key=${environment.getRequiredProperty(key)}"
        } + listOf("--datasource.mysql-jpa.main.maximum-pool-size=3", "--datasource.mysql-jpa.main.minimum-idle=1")

    private fun draft(productId: Long): Long = post("/api/v1/orders", mapOf("items" to listOf(mapOf("productId" to productId, "quantity" to 1))))
        .expect(HttpStatus.CREATED).path("id").asLong()

    private fun start(orderId: Long) = post(
        "/api/v1/orders/$orderId/payment",
        mapOf("paymentAttemptKey" to "incident-$orderId", "cardType" to "TEST", "cardNo" to "0000-0000-0000-0000"),
    )

    private fun confirmNormally(orderId: Long) {
        assertThat(start(orderId).expect(HttpStatus.ACCEPTED).path("paymentStatus").asText()).isEqualTo("PENDING")
        val key = provider.transactionsOf(PaymentAttempt.providerOrderIdOf(orderId)).single().transactionKey
        provider.complete(key, "SUCCESS")
        callback(orderId, key)
    }

    private fun callback(orderId: Long, key: String) {
        post(
            "/api/v1/payments/callback",
            mapOf("orderId" to PaymentAttempt.providerOrderIdOf(orderId), "transactionKey" to key, "status" to "SUCCESS"),
        ).expect(HttpStatus.OK)
    }

    private fun reconcile(orderId: Long) = post("/api/v1/orders/$orderId/payment/reconcile")

    private fun get(path: String) = http.exchange(path, HttpMethod.GET, HttpEntity<Unit>(headers(BUYER)), JsonNode::class.java)

    private fun post(path: String, body: Any? = null, login: String = BUYER) =
        http.exchange(path, HttpMethod.POST, HttpEntity(body, headers(login)), JsonNode::class.java)

    private fun headers(login: String) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-USER-ID", login)
    }

    private fun ResponseEntity<JsonNode>.expect(status: HttpStatus): JsonNode {
        assertThat(statusCode).withFailMessage("HTTP %s 응답 기대, 실제 %s %s", status, statusCode, body).isEqualTo(status)
        return requireNotNull(body).path("data")
    }

    private fun save(evidence: Path, fileName: String, value: Any) {
        Files.writeString(evidence.resolve(fileName), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), CREATE_NEW)
    }

    private fun sha256(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    @TestConfiguration(proxyBeanMethods = false)
    class IncidentConfig {
        @Primary
        @Bean
        fun incidentClock(): Clock = Clock.fixed(confirmedTime, ZoneOffset.UTC)
    }
}
