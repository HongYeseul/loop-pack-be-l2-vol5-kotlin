package com.loopers.purchase

import com.loopers.purchase.domain.ConfirmedPurchase
import com.loopers.purchase.domain.OrderSourceRepository
import com.loopers.purchase.infrastructure.OrderItemSourceEntity
import com.loopers.purchase.infrastructure.OrderSourceEntity
import com.loopers.testcontainers.MySqlTestContainersConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["commerce.kafka.purchase-activity-enabled=false", "spring.jpa.show-sql=false"],
)
@ContextConfiguration(initializers = [OrderSourceJpaRepositoryTest.SourceSchemaInitializer::class])
@DisplayName("JPA·JPQL 주문 원본 조회와 읽기 전용 매핑")
class OrderSourceJpaRepositoryTest @Autowired constructor(
    private val source: OrderSourceRepository,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    @Qualifier("purchaseSchemaCustomizer") private val customizer: HibernatePropertiesCustomizer,
) {
    @Test
    @DisplayName("streamer의 자동 DDL 생성 시 기존 주문 테이블과 원본 데이터를 보존한다")
    fun preservesExistingSourceWhenCreatingSchema() {
        assertThat(source.findConfirmed(9001)).isEqualTo(PurchaseEvents.purchase(9001))
        assertThat(jdbc.queryForObject("SELECT source_marker FROM orders WHERE id=9001", String::class.java)).isEqualTo("api-owned")
    }

    @Test
    @DisplayName("streamer의 자동 DDL 업데이트 시 주문 원본의 컬럼 정의를 변경하지 않는다")
    fun excludesSourceTablesFromSchemaUpdate() {
        val properties = mutableMapOf<String, Any>()
        customizer.customize(properties)
        val registry = StandardServiceRegistryBuilder()
            .applySettings(properties)
            .applySetting("hibernate.connection.datasource", dataSource)
            .applySetting("hibernate.hbm2ddl.auto", "update")
            .build()
        try {
            MetadataSources(registry)
                .addAnnotatedClass(OrderSourceEntity::class.java)
                .addAnnotatedClass(OrderItemSourceEntity::class.java)
                .buildMetadata().buildSessionFactory().use { }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT column_type FROM information_schema.columns " +
                    "WHERE table_schema=DATABASE() AND table_name='orders' AND column_name='status'",
                String::class.java,
            ),
        ).isEqualTo("varchar(24)")
        assertThat(source.findConfirmed(9001)).isEqualTo(PurchaseEvents.purchase(9001))
    }

    @ParameterizedTest(name = "상태={1}, 확정 시각 없음")
    @CsvSource("9002,DRAFT", "9003,CANCELED", "9004,CONFIRMED")
    @DisplayName("유효한 확정 사실이 없는 주문은 조회하지 않는다")
    fun excludesOrdersWithoutConfirmation(orderId: Long, status: String) {
        jdbc.update("INSERT INTO orders(id,status,confirmed_at) VALUES (?,?,NULL)", orderId, status)

        assertThat(source.findConfirmed(orderId)).isNull()
    }

    @Test
    @DisplayName("존재하지 않는 주문은 조회 결과가 없다")
    fun returnsNullForMissingOrder() {
        assertThat(source.findConfirmed(9999)).isNull()
    }

    @Test
    @DisplayName("JPQL로 같은 상품의 수량을 합산하고 상품 ID순으로 조회하며 최초 확정 시각을 유지한다")
    fun aggregatesItemsAndPreservesMicrosecondTimestamp() {
        jdbc.update("INSERT INTO orders(id,status,confirmed_at) VALUES (9005,'CONFIRMED','2026-10-27 01:02:03.123456')")
        jdbc.update("INSERT INTO order_item(order_id,product_id,quantity) VALUES (9005,8,1),(9005,7,1),(9005,7,2)")
        assertThat(source.findConfirmed(9005)).isEqualTo(
            ConfirmedPurchase(9005, PurchaseEvents.time, listOf(ConfirmedPurchase.Item(7, 3), ConfirmedPurchase.Item(8, 1))),
        )
    }

    @Test
    @DisplayName("조회용 엔티티의 값을 변경해도 flush와 commit으로 주문·품목 원본을 수정하지 않는다")
    fun doesNotFlushChangesToSourceEntities() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            val order = entityManager.find(OrderSourceEntity::class.java, 9001L)
            val item = entityManager.find(OrderItemSourceEntity::class.java, 1L)
            ReflectionTestUtils.setField(order, "status", "CANCELED")
            ReflectionTestUtils.setField(item, "quantity", 99)
            entityManager.flush()
        }
        assertThat(source.findConfirmed(9001)).isEqualTo(PurchaseEvents.purchase(9001))
    }

    class SourceSchemaInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(applicationContext: ConfigurableApplicationContext) {
            MySqlTestContainersConfig()
            DriverManager.getConnection(
                System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                System.getProperty("datasource.mysql-jpa.main.username"),
                System.getProperty("datasource.mysql-jpa.main.password"),
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS order_item")
                    statement.execute("DROP TABLE IF EXISTS orders")
                    statement.execute(
                        "CREATE TABLE orders (id BIGINT PRIMARY KEY, status VARCHAR(24), confirmed_at DATETIME(6), " +
                            "source_marker VARCHAR(16) DEFAULT 'api-owned')",
                    )
                    statement.execute(
                        "CREATE TABLE order_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, product_id BIGINT, quantity INT)",
                    )
                    statement.execute("INSERT INTO orders(id,status,confirmed_at) VALUES (9001,'CONFIRMED','2026-10-27 01:02:03.123456')")
                    statement.execute("INSERT INTO order_item(order_id,product_id,quantity) VALUES (9001,7,2),(9001,8,1)")
                }
            }
        }
    }
}
