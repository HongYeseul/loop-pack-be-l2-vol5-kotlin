package coupon.jpa.support

import com.loopers.coupon.application.CouponReservedIssuingService
import com.loopers.coupon.application.CouponQueryService
import com.loopers.coupon.domain.CouponCampaign
import com.loopers.coupon.infrastructure.CouponCampaignJpaRepository
import com.loopers.coupon.infrastructure.CouponJpaRepository
import com.loopers.testcontainers.MySqlTestContainersConfig
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier

class ReservedCouponDatabaseFixture(quota: Int = 100, enabled: Boolean = true) : AutoCloseable {
    private val databaseName = "coupon_${UUID.randomUUID().toString().replace("-", "")}"
    val context = AnnotationConfigApplicationContext()
    val issuer: CouponReservedIssuingService
    val query: CouponQueryService
    val campaigns: CouponCampaignJpaRepository
    val coupons: CouponJpaRepository
    val campaign: CouponCampaign

    init {
        MySqlTestContainersConfig()
        admin { it.execute("CREATE DATABASE $databaseName") }
        try {
            val username = System.getProperty("datasource.mysql-jpa.main.username")
            require(username.matches(Regex("[A-Za-z0-9_]+")))
            admin { it.execute("GRANT ALL ON $databaseName.* TO '$username'@'%'") }
            val properties = mapOf(
                "spring.datasource.url" to databaseUrl(),
                "spring.datasource.username" to username,
                "spring.datasource.password" to System.getProperty("datasource.mysql-jpa.main.password"),
                "spring.datasource.hikari.maximum-pool-size" to "10",
                "spring.datasource.hikari.minimum-idle" to "5",
                "spring.datasource.hikari.connection-init-sql" to "SET SESSION innodb_lock_wait_timeout=5",
                "spring.jpa.hibernate.ddl-auto" to "create-drop",
                "spring.jpa.open-in-view" to "false",
            )
            context.environment.propertySources.addFirst(MapPropertySource("coupon-database-test", properties))
            context.registerBean(
                "couponTestClock",
                Clock::class.java,
                Supplier { Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC) },
            )
            context.register(ReservedCouponDatabaseConfiguration::class.java)
            context.refresh()
            issuer = context.getBean(CouponReservedIssuingService::class.java)
            query = context.getBean(CouponQueryService::class.java)
            campaigns = context.getBean(CouponCampaignJpaRepository::class.java)
            coupons = context.getBean(CouponJpaRepository::class.java)
            campaign = campaigns.saveAndFlush(CouponCampaign("행사", quota, "5,000원 할인권", 5000, enabled))
        } catch (error: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    fun issuedCount(): Int = campaigns.findById(campaign.id).orElseThrow().issuedCount

    fun deactivate() {
        val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        transaction.executeWithoutResult { campaigns.findById(campaign.id).orElseThrow().deactivate() }
    }

    fun failCouponInserts() {
        admin(inTestDatabase = true) {
            it.execute(
                "CREATE TRIGGER fail_coupon_insert BEFORE INSERT ON coupon " +
                    "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='coupon test insert failure'",
            )
        }
    }

    fun uniqueDiscountAmount() {
        admin(inTestDatabase = true) {
            it.execute("ALTER TABLE coupon ADD CONSTRAINT uk_test_coupon_discount UNIQUE (discount_amount)")
        }
    }

    private fun databaseUrl(): String = System.getProperty("datasource.mysql-jpa.main.jdbc-url")
        .substringBefore('?').substringBeforeLast('/') + "/$databaseName?connectTimeout=5000&socketTimeout=15000"

    private fun admin(inTestDatabase: Boolean = false, work: (java.sql.Statement) -> Unit) {
        val url = if (inTestDatabase) databaseUrl() else System.getProperty("datasource.mysql-jpa.main.jdbc-url")
        DriverManager.getConnection(url, "root", System.getProperty("datasource.mysql-jpa.main.password")).use { c ->
            c.createStatement().use { s ->
                s.queryTimeout = 10
                work(s)
            }
        }
    }

    override fun close() {
        try {
            context.close()
        } finally {
            admin { it.execute("DROP DATABASE IF EXISTS $databaseName") }
        }
    }
}
