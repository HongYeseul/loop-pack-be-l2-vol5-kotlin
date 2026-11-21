package com.loopers.job.ranking.benchmark

import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishStepConfiguration
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishTasklet
import com.loopers.batch.job.ranking.step.score.WeeklyRankingReaderConfiguration
import com.loopers.batch.job.ranking.step.score.WeeklyRankingWriter
import com.loopers.batch.listener.StepMonitorListener
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.init.DataSourceInitializer
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.support.JdbcTransactionManager
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

@TestConfiguration(proxyBeanMethods = false)
@EnableBatchProcessing
@Import(
    RankingBenchmarkScoreReaderConfiguration::class,
    RankingBenchmarkScoreStepConfiguration::class,
    WeeklyRankingReaderConfiguration::class,
    WeeklyRankingWriter::class,
    WeeklyRankingPublishStepConfiguration::class,
    WeeklyRankingPublishTasklet::class,
    StepMonitorListener::class,
)
class WeeklyRankingScoreBenchmarkConfig {
    @Bean(destroyMethod = "stop")
    fun benchmarkMySql(): MySQLContainer<*> =
        MySQLContainer<Nothing>(DockerImageName.parse(MYSQL_IMAGE)).apply {
            withDatabaseName("ranking_benchmark")
            withUsername("ranking_benchmark")
            withPassword("benchmark-test")
            withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_general_ci",
                "--performance-schema=ON",
                "--skip-log-bin",
            )
            start()
        }

    @Bean
    fun benchmarkPool(benchmarkMySql: MySQLContainer<*>, counter: BenchmarkJdbcCounter): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = benchmarkMySql.jdbcUrl
                username = benchmarkMySql.username
                password = benchmarkMySql.password
                driverClassName = "com.mysql.cj.jdbc.Driver"
                maximumPoolSize = 10
                minimumIdle = 5
                connectionInitSql = "SET SESSION innodb_lock_wait_timeout = 3"
                addDataSourceProperty("rewriteBatchedStatements", true)
                metricsTrackerFactory = MetricsTrackerFactory { _, _ -> counter }
            },
        )

    @Bean
    fun benchmarkJdbcCounter() = BenchmarkJdbcCounter()

    @Bean
    @Primary
    fun dataSource(
        @Qualifier("benchmarkPool") pool: HikariDataSource,
        counter: BenchmarkJdbcCounter,
    ): DataSource = ProxyDataSourceBuilder.create(pool).listener(counter).build()

    @Bean
    fun transactionManager(dataSource: DataSource) = JdbcTransactionManager(dataSource)

    @Bean
    fun jdbcTemplate(dataSource: DataSource) = JdbcTemplate(dataSource)

    @Bean
    fun namedParameterJdbcTemplate(jdbc: JdbcTemplate) = NamedParameterJdbcTemplate(jdbc)

    @Bean
    fun benchmarkSchema(dataSource: DataSource) = DataSourceInitializer().apply {
        setDataSource(dataSource)
        setDatabasePopulator(
            ResourceDatabasePopulator(
                ClassPathResource("org/springframework/batch/core/schema-mysql.sql"),
                ClassPathResource("db/weekly-ranking.sql"),
                ClassPathResource("db/weekly-ranking-benchmark.sql"),
            ),
        )
    }

    companion object {
        const val MYSQL_IMAGE = "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b"
    }
}
