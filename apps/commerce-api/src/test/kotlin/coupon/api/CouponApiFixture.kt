package coupon.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.CommerceApiApplication
import com.loopers.coupon.application.CouponStreamProcessor
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.infrastructure.CouponCampaignJpaRepository
import com.loopers.coupon.infrastructure.CouponJpaRepository
import com.loopers.coupon.stream.infrastructure.RedisCouponExecutionGate
import com.loopers.testcontainers.RedisTestContainer
import com.loopers.fixture.UserFixture
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.user.infrastructure.UserJpaRepository
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class CouponApiFixture(
    val server: RedisTestContainer,
    held: Boolean = false,
    val operations: Boolean = false,
    releaseHook: Boolean = false,
    responseLoss: Boolean = false,
    localFixture: Boolean = false,
    workerEnabled: Boolean = false,
    quota: Int = 100,
    userCount: Int = 4,
) : AutoCloseable {
    private val database = "coupon_api_${UUID.randomUUID().toString().replace("-", "")}"
    val key = "coupon:api:${UUID.randomUUID()}"
    lateinit var context: ConfigurableApplicationContext
        private set
    lateinit var campaigns: CouponCampaignJpaRepository
        private set
    lateinit var coupons: CouponJpaRepository
        private set
    lateinit var campaign: CouponCampaign
        private set
    val client = TestRestTemplate().apply {
        restTemplate.requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3000)
            setReadTimeout(15000)
        }
    }
    lateinit var baseUrl: String
        private set

    private var bootArgs = emptyArray<String>()
    private var consumerPool: ScheduledExecutorService? = null
    val stream: CouponStream get() = context.getBean(CouponStream::class.java)
    val gate: CouponExecutionGate get() = context.getBean(CouponExecutionGate::class.java)
    val streamPrefix: String get() = "$key:stock"
    val group: String = "coupon-issuers"

    init {
        MySqlTestContainersConfig()
        admin("CREATE DATABASE $database")
        val username = System.getProperty("datasource.mysql-jpa.main.username")
        require(username.matches(Regex("[A-Za-z0-9_]+")))
        admin("GRANT ALL ON $database.* TO '$username'@'%'")
        val url = System.getProperty("datasource.mysql-jpa.main.jdbc-url")
            .substringBefore('?').substringBeforeLast('/') + "/$database"
        val sources = mutableListOf<Class<*>>(CommerceApiApplication::class.java)
        if (held) sources.add(HeldAdmissionConfiguration::class.java)
        if (releaseHook) sources.add(ReleaseHookConfiguration::class.java)
        if (responseLoss) sources.add(ResponseLossConfiguration::class.java)
        var started: ConfigurableApplicationContext? = null
        try {
            bootArgs = arrayOf(
                    "--spring.profiles.active=${if (operations) "test,local" else "test"}",
                    "--server.port=0", "--server.address=127.0.0.1", "--management.server.port=0",
                    "--datasource.mysql-jpa.main.jdbc-url=$url",
                    "--datasource.mysql-jpa.main.username=$username",
                    "--datasource.mysql-jpa.main.password=${System.getProperty("datasource.mysql-jpa.main.password")}",
                    "--spring.jpa.hibernate.ddl-auto=update",
                    "--datasource.redis.master.host=${server.host}", "--datasource.redis.master.port=${server.firstMappedPort}",
                    "--datasource.redis.replicas[0].host=${server.host}",
                    "--datasource.redis.replicas[0].port=${server.firstMappedPort}",
                    "--datasource.redis.command-timeout=300ms",
                    "--coupon.stream.prefix=$key:stock", "--coupon.stream.control-prefix=$key:control",
                    "--coupon.operations.enabled=$operations", "--coupon.fixture.enabled=$localFixture",
                    "--coupon.operations.username=test-operator", "--coupon.operations.password=test-password",
                )
            context = SpringApplicationBuilder(*sources.toTypedArray())
                .web(WebApplicationType.SERVLET).registerShutdownHook(false).run(*bootArgs)
            started = context
            campaigns = context.getBean(CouponCampaignJpaRepository::class.java)
            coupons = context.getBean(CouponJpaRepository::class.java)
            campaign = campaigns.saveAndFlush(CouponCampaign("테스트 행사", quota, "5,000원 할인권", 5000))
            val users = context.getBean(UserJpaRepository::class.java)
            (1..userCount).forEach { users.saveAndFlush(UserFixture.user(loginId = "user$it")) }
            if (!operations) context.getBean(com.loopers.coupon.application.CouponAdmissionOperations::class.java).recover(true)
            baseUrl = "http://127.0.0.1:${context.environment.getRequiredProperty("local.server.port")}"
            if (workerEnabled) startTestConsumers()
            check(context.getBean(com.loopers.config.redis.RedisProperties::class.java).master.port == server.firstMappedPort)
        } catch (e: Throwable) {
            started?.close()
            admin("DROP DATABASE IF EXISTS $database")
            throw e
        }
    }

    fun issue(user: String = "user1", dropResponse: Boolean = false): ResponseEntity<JsonNode> = client.exchange(
        "$baseUrl/api/v1/coupon-campaigns/${campaign.id}/issues",
        HttpMethod.POST,
        HttpEntity(
            null,
            HttpHeaders().apply {
                set("X-USER-ID", user)
                if (dropResponse) set("X-TEST-DROP-RESPONSE", "true")
            },
        ),
        JsonNode::class.java,
    )

    fun restartServer() {
        val campaignId = campaign.id
        stopTestConsumers()
        context.close()
        context = SpringApplicationBuilder(CommerceApiApplication::class.java)
            .web(WebApplicationType.SERVLET).registerShutdownHook(false).run(*bootArgs)
        campaigns = context.getBean(CouponCampaignJpaRepository::class.java)
        coupons = context.getBean(CouponJpaRepository::class.java)
        campaign = campaigns.findById(campaignId).orElseThrow()
        baseUrl = "http://127.0.0.1:${context.environment.getRequiredProperty("local.server.port")}"
    }

    fun processOne(consumer: String = "test-consumer"): Boolean =
        context.getBean(CouponStreamProcessor::class.java).processOne(campaign.id, group, consumer, 30000)

    fun result(user: String = "user1", campaignId: Long = campaign.id): ResponseEntity<JsonNode> = client.exchange(
        "$baseUrl/api/v1/coupon-campaigns/$campaignId/issue",
        HttpMethod.GET,
        HttpEntity(null, HttpHeaders().apply { set("X-USER-ID", user) }),
        JsonNode::class.java,
    )

    private fun startTestConsumers() {
        consumerPool = Executors.newScheduledThreadPool(3).also { pool ->
            repeat(3) { index ->
                pool.scheduleWithFixedDelay({ runCatching { processOne("test-consumer-$index") } }, 0, 25, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun stopTestConsumers() {
        consumerPool?.let {
            it.shutdownNow()
            check(it.awaitTermination(10, TimeUnit.SECONDS))
        }
        consumerPool = null
    }

    fun allowCouponInserts() = admin("DROP TRIGGER $database.reject_coupon")

    fun owned(user: String = "user1"): ResponseEntity<JsonNode> = client.exchange(
        "$baseUrl/api/v1/coupons",
        HttpMethod.GET,
        HttpEntity(null, HttpHeaders().apply { set("X-USER-ID", user) }),
        JsonNode::class.java,
    )

    fun operate(path: String, body: Map<String, Any>? = null): ResponseEntity<JsonNode> =
        client.withBasicAuth("test-operator", "test-password").exchange(
            "$baseUrl/api-admin/v1/local/coupon-admission$path",
            if (path.isEmpty()) HttpMethod.GET else HttpMethod.POST,
            if (body == null) HttpEntity.EMPTY else HttpEntity(body),
            JsonNode::class.java,
        )

    fun failCouponInserts() {
        admin(
            "CREATE TRIGGER $database.reject_coupon BEFORE INSERT ON $database.coupon " +
                "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'coupon insert rejected'",
        )
    }

    private fun admin(sql: String) {
        DriverManager.getConnection(
            System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            "root",
            System.getProperty("datasource.mysql-jpa.main.password"),
        ).use { c ->
            c.createStatement().use {
                it.queryTimeout = 10
                it.execute(sql)
            }
        }
    }

    override fun close() {
        try {
            stopTestConsumers()
            context.close()
        } finally {
            admin("DROP DATABASE IF EXISTS $database")
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class HeldAdmissionConfiguration {
    @Bean
    @Primary
    fun heldAdmission(real: RedisCouponExecutionGate): HeldAdmission = HeldAdmission(real)
}

class HeldAdmission(private val real: RedisCouponExecutionGate) : CouponExecutionGate {
    val entered = CountDownLatch(3)
    val proceed = Semaphore(0)
    private val calls = AtomicInteger()

    override fun acquire(): String? {
        val token = real.acquire() ?: return null
        if (calls.incrementAndGet() <= 3) {
            entered.countDown()
            try {
                check(proceed.tryAcquire(20, TimeUnit.SECONDS))
            } catch (e: Exception) {
                real.release(token)
                throw e
            }
        }
        return token
    }

    override fun release(token: String) = real.release(token)
    override fun retainUncertain(token: String) = real.retainUncertain(token)
    override fun status(): CouponGateStatus = real.status()
    override fun close(): CouponGateStatus = real.close()
    override fun recover(
        confirmed: Boolean,
        expectedTokens: Set<String>,
    ): CouponGateStatus = real.recover(confirmed, expectedTokens)
}
