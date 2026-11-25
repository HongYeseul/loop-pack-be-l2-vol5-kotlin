package com.loopers.job.ranking.benchmark

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.useLines
import kotlin.math.ceil

internal class RankingApiLoadHarness(
    private val jdbc: JdbcTemplate,
    private val mysql: MySQLContainer<*>,
    private val output: Path,
    private val rate: Int,
) {
    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private val jar = Path.of(
        requireNotNull(System.getenv("RANKING_API_JAR")) { "RANKING_API_JAR에 commerce-api bootJar 경로를 지정하세요." },
    )
    private val k6 = System.getenv("K6_BIN") ?: "k6"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val mapper = ObjectMapper()
    private val loadScript = output.resolve("ranking-order-load.js").also { target ->
        requireNotNull(javaClass.getResourceAsStream("/ranking-order-load.js")).use { Files.copy(it, target) }
    }

    init {
        require(Files.isRegularFile(jar)) { "API jar를 찾을 수 없습니다. $jar" }
        require(rate > 0)
    }

    fun startApi(): RankingLoadApi {
        val log = output.resolve("commerce-api.log")
        val process = ProcessBuilder(
            java,
            "-jar",
            jar.toString(),
            "--spring.profiles.active=test",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--management.server.port=0",
            "--management.server.address=127.0.0.1",
            "--spring.jpa.hibernate.ddl-auto=update",
            "--spring.jpa.show-sql=false",
            "--spring.main.banner-mode=off",
            "--spring.output.ansi.enabled=never",
            "--datasource.mysql-jpa.main.jdbc-url=${mysql.jdbcUrl}",
            "--datasource.mysql-jpa.main.username=${mysql.username}",
            "--datasource.mysql-jpa.main.password=${mysql.password}",
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            var ports = emptyList<String>()
            await(process, log, Duration.ofSeconds(90)) {
                val startup = log.readText()
                ports = TOMCAT_PORT.findAll(startup).map { it.groupValues[1] }.toList()
                ports.size == 2 && startup.contains("Started CommerceApiApplicationKt")
            }
            val (apiPort, actuatorPort) = ports
            return RankingLoadApi(process, "http://127.0.0.1:$apiPort", "http://127.0.0.1:$actuatorPort")
        } catch (failure: Throwable) {
            stop(process)
            throw failure
        }
    }

    fun measure(api: RankingLoadApi, name: String, measureWindows: () -> List<RankingLoadWindow>): List<RankingLoadResult> {
        resetOrders()
        val samples = output.resolve("$name.ndjson")
        val log = output.resolve("$name-k6.log")
        val process = ProcessBuilder(k6, "run", "--out", "json=$samples", loadScript.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply {
                environment().putAll(
                    mapOf(
                        "ORDER_API_URL" to api.url,
                        "ORDER_RATE" to rate.toString(),
                        "ORDER_ACTORS" to ACTORS.toString(),
                        "LOAD_DURATION" to "${MAX_LOAD_SECONDS}s",
                    ),
                )
            }
            .start()
        val windows = try {
            await(process, log, Duration.ofSeconds(25)) {
                Files.exists(samples) && samples.useLines { lines ->
                    lines.any { it.contains("\"type\":\"Point\"") && it.contains("\"metric\":\"checks\"") }
                }
            }
            Thread.sleep(Duration.ofSeconds(12))
            check(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long::class.javaObjectType)!! > 0) {
                "예열 중 확정 주문이 없습니다. $log"
            }
            println("$name 부하 측정 시작")
            measureWindows().also { Thread.sleep(Duration.ofSeconds(2)) }
        } finally {
            stop(process)
        }
        check(process.exitValue() in setOf(0, K6_EXTERNAL_ABORT)) { "k6 종료 코드 ${process.exitValue()}. $log" }
        val points = readSamples(samples)
        val finalRequest = points.lastOrNull { it.metric == "http_req_duration" }?.time
        check(finalRequest != null && windows.all { finalRequest >= it.end }) {
            "ScoreStep 종료까지 주문 부하가 유지되지 않았습니다. $log"
        }
        return windows.map { window ->
            val matching = points.filter { it.time >= window.start && it.time < window.end }
            val timings = matching.filter { it.metric == "http_req_duration" }.map { it.value }.sorted()
            val checks = matching.filter { it.metric == "checks" }
            check(timings.isNotEmpty() && checks.isNotEmpty()) { "측정 구간에 완료된 주문 요청이 없습니다. $log" }
            RankingLoadResult(
                window = window,
                requests = timings.size,
                failed = checks.count { it.value == 0.0 },
                dropped = matching.filter { it.metric == "dropped_iterations" }.sumOf { it.value.toLong() },
                averageMillis = timings.average(),
                p95Millis = timings[ceil(timings.size * 0.95).toInt() - 1],
            )
        }
    }

    fun counters(api: RankingLoadApi): RankingLoadCounters {
        val scrape = http.send(
            HttpRequest.newBuilder(URI.create("${api.actuatorUrl}/actuator/prometheus")).timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(scrape.statusCode() == 200) { "API Prometheus 응답 상태 ${scrape.statusCode()}" }
        val status = jdbc.query(
            """
            SHOW GLOBAL STATUS WHERE Variable_name IN (
                'Innodb_data_read', 'Innodb_data_written', 'Innodb_row_lock_time', 'Innodb_row_lock_waits'
            )
            """.trimIndent(),
            { row, _ -> row.getString(1).lowercase() to row.getLong(2) },
        ).toMap()
        val cpu = mysql.execInContainer("cat", "/sys/fs/cgroup/cpu.stat")
        return RankingLoadCounters(
            cpuMicros = CPU_USAGE.find(cpu.stdout)?.groupValues?.get(1)?.toLong(),
            readBytes = status.getValue("innodb_data_read"),
            writtenBytes = status.getValue("innodb_data_written"),
            lockMillis = status.getValue("innodb_row_lock_time"),
            lockWaits = status.getValue("innodb_row_lock_waits"),
            acquireSeconds = prometheusCounter(scrape.body(), "hikaricp_connections_acquire_seconds_sum"),
            acquireCount = prometheusCounter(scrape.body(), "hikaricp_connections_acquire_seconds_count").toLong(),
        )
    }

    private fun prometheusCounter(body: String, name: String): Double =
        body.lineSequence().single { it.startsWith("$name{") && it.contains("pool=\"mysql-main-pool\"") }
            .substringAfter("} ").substringBefore(' ').toDouble()

    private fun readSamples(path: Path): List<K6Point> = path.useLines { lines ->
        lines.mapNotNull { line ->
            val point = mapper.readTree(line)
            val metric = point.path("metric").asText()
            if (point.path("type").asText() != "Point" || metric !in K6_METRICS) return@mapNotNull null
            val data = point.path("data")
            K6Point(metric, Instant.parse(data.path("time").asText()), data.path("value").asDouble())
        }.sortedBy { it.time }.toList()
    }

    private fun resetOrders() {
        for (table in listOf("outbox", "point_transaction", "order_item", "orders", "point", "product", "user", "brand")) {
            jdbc.execute("TRUNCATE TABLE $table")
        }
        jdbc.update(
            "INSERT INTO brand (id, name, created_at, updated_at) VALUES (1, 'Load brand', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
        )
        val actors = (1..ACTORS).map { arrayOf<Any>(it) }
        jdbc.batchUpdate(
            """
            INSERT INTO user (id, login_id, display_name, status, created_at, updated_at)
            VALUES (?, CONCAT('loaduser', ?), 'Load user', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
            (1..ACTORS).map { arrayOf<Any>(it, it) },
        )
        jdbc.batchUpdate(
            """
            INSERT INTO point (user_id, balance, created_at, updated_at)
            VALUES (?, 100000000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
            actors,
        )
        jdbc.batchUpdate(
            """
            INSERT INTO product (id, brand_id, name, price, stock, status, created_at, updated_at)
            VALUES (?, 1, 'Load product', 100, 50000, 'ON_SALE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
            actors,
        )
        for (batch in (1..(rate * MAX_LOAD_SECONDS)).chunked(1_000)) {
            jdbc.batchUpdate(
                """
                INSERT INTO orders (id, user_id, status, total_amount, expires_at, created_at, updated_at)
                VALUES (?, ?, 'DRAFT', 100, UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """.trimIndent(),
                batch.map { arrayOf<Any>(it, (it - 1) % ACTORS + 1) },
            )
        }
        jdbc.update(
            """
            INSERT INTO order_item (order_id, product_id, quantity, unit_price, created_at, updated_at)
            SELECT id, user_id, 1, 100, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM orders
            """.trimIndent(),
        )
    }

    private fun await(process: Process, log: Path, timeout: Duration, ready: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (process.isAlive && System.nanoTime() < deadline) {
            if (ready()) return
            Thread.sleep(200)
        }
        error("제한 시간에 프로세스 준비를 확인할 수 없습니다. $log\n${log.readText().takeLast(4_000)}")
    }

    private data class K6Point(val metric: String, val time: Instant, val value: Double)

    private companion object {
        const val ACTORS = 128
        const val MAX_LOAD_SECONDS = 300
        const val K6_EXTERNAL_ABORT = 105
        val TOMCAT_PORT = Regex("Tomcat started on port (\\d+)")
        val CPU_USAGE = Regex("(?m)^usage_usec\\s+(\\d+)$")
        val K6_METRICS = setOf("http_req_duration", "checks", "dropped_iterations")
    }
}

internal class RankingLoadApi(private val process: Process, val url: String, val actuatorUrl: String) : AutoCloseable {
    override fun close() = stop(process)
}

private fun stop(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "부하 테스트 프로세스 종료 실패 ${process.pid()}" }
    }
}

internal data class RankingLoadWindow(val start: Instant, val end: Instant, val counters: RankingLoadCounters) {
    val duration: Duration get() = Duration.between(start, end)
}

internal data class RankingLoadCounters(
    val cpuMicros: Long?,
    val readBytes: Long,
    val writtenBytes: Long,
    val lockMillis: Long,
    val lockWaits: Long,
    val acquireSeconds: Double,
    val acquireCount: Long,
) {
    operator fun minus(before: RankingLoadCounters) = RankingLoadCounters(
        cpuMicros = if (cpuMicros != null && before.cpuMicros != null) cpuMicros - before.cpuMicros else null,
        readBytes = readBytes - before.readBytes,
        writtenBytes = writtenBytes - before.writtenBytes,
        lockMillis = lockMillis - before.lockMillis,
        lockWaits = lockWaits - before.lockWaits,
        acquireSeconds = acquireSeconds - before.acquireSeconds,
        acquireCount = acquireCount - before.acquireCount,
    )
}

internal data class RankingLoadResult(
    val window: RankingLoadWindow,
    val requests: Int,
    val failed: Int,
    val dropped: Long,
    val averageMillis: Double,
    val p95Millis: Double,
) {
    fun toCsv(condition: String): String {
        val seconds = window.duration.toNanos() / 1e9
        val counters = window.counters
        return listOf(
            condition, window.start, window.end, seconds, requests, failed, dropped, averageMillis, p95Millis,
            counters.cpuMicros?.let { it / 1e6 / seconds } ?: "", counters.readBytes, counters.writtenBytes,
            counters.lockWaits, counters.lockMillis, counters.acquireCount,
            if (counters.acquireCount > 0) counters.acquireSeconds * 1000 / counters.acquireCount else "",
        ).joinToString(",")
    }

    companion object {
        const val CSV_HEADER = "condition,start,end,window_seconds,requests,failed,dropped,api_avg_ms,api_p95_ms," +
            "mysql_avg_cpu_cores,innodb_read_bytes,innodb_written_bytes,innodb_row_lock_waits,innodb_row_lock_ms," +
            "api_connection_acquires,api_connection_acquire_avg_ms"
    }
}
