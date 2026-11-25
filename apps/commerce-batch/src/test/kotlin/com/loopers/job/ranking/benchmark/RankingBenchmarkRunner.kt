package com.loopers.job.ranking.benchmark

import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.GROUPED_SCORES_SQL
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.purchasePagingQueryProvider
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreReaderConfiguration.Companion.scorePagingQueryProvider
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration.Companion.CHUNK_SIZE
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration.Companion.SCORES_BY_PRODUCT_IDS_SQL
import com.loopers.batch.job.ranking.benchmark.step.score.SqlWeeklyRankingScoreTasklet.Companion.AGGREGATE_AND_SAVE_SQL
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishStepConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.Step
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.step.tasklet.TaskletStep
import org.springframework.batch.item.database.PagingQueryProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.MySQLContainer
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.sql.DataSource
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

internal class RankingBenchmarkRunner(
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
    private val container: MySQLContainer<*>,
    private val jdbcCounter: BenchmarkJdbcCounter,
) {
    fun run(
        scoreSteps: Map<RankingBenchmarkMethod, Step>,
        launch: (RankingBenchmarkMethod, Step, String, WeeklyRankingPeriod) -> JobExecution,
    ) {
        val suite = System.getenv("RANKING_BENCHMARK_SUITE") ?: "smoke"
        require(suite in setOf("smoke", "full", "optimized")) { "RANKING_BENCHMARK_SUITE는 smoke, full, optimized 중 하나여야 합니다" }
        val selected = System.getenv("RANKING_BENCHMARK_CASE")?.split(',')?.map(String::trim)?.toSet()
        val methodNames = System.getenv("RANKING_BENCHMARK_METHODS")?.split(',')?.map(String::trim)?.toSet()
        require(methodNames == null || methodNames.all { name -> RankingBenchmarkMethod.entries.any { it.name == name } }) {
            "RANKING_BENCHMARK_METHODS에는 A, B, C_CURSOR, C_PAGING, D를 지정합니다"
        }
        val selectedSteps = scoreSteps.filterKeys { methodNames == null || it.name in methodNames }
        val distribution = System.getenv("RANKING_BENCHMARK_DISTRIBUTION")
        val pkOrder = System.getenv("RANKING_BENCHMARK_PK_ORDER")
        val trial = System.getenv("RANKING_BENCHMARK_TRIAL") == "true"
        val rounds = if (trial) 1..1 else 0..REPETITIONS
        require(distribution == null || distribution in setOf("uniform", "skewed")) {
            "RANKING_BENCHMARK_DISTRIBUTION은 uniform, skewed 중 하나여야 합니다"
        }
        require(pkOrder == null || PurchaseOrder.entries.any { it.name.equals(pkOrder, ignoreCase = true) }) {
            "RANKING_BENCHMARK_PK_ORDER는 grouped, shuffled 중 하나여야 합니다"
        }
        val workloads = workloads()
        val knownSizes = workloads.map { "${it.products}x${it.purchases}" }.toSet()
        val unknownSizes = selected.orEmpty() - knownSizes
        require(unknownSizes.isEmpty()) { "등록되지 않은 상품 수와 구매 행 수 조건입니다: $unknownSizes" }
        val cases = workloads.filter { workload ->
            val distributionMatches = distribution == null || workload.skewed == (distribution == "skewed")
            val orderMatches = pkOrder == null || workload.order.name.equals(pkOrder, ignoreCase = true)
            if (!distributionMatches || !orderMatches) return@filter false
            when {
                selected != null -> "${workload.products}x${workload.purchases}" in selected
                suite == "smoke" -> workload.products == 1_000 && workload.purchases == 10_000
                suite == "optimized" -> workload.products == 1_000 && workload.purchases == 1_000_000
                else -> true
            }
        }
        require(cases.any { it.supportsDistribution }) { "실행 가능한 조건을 찾을 수 없습니다: $selected $distribution $pkOrder" }
        val outputBase = Path.of(System.getenv("RANKING_BENCHMARK_OUTPUT") ?: "build/weekly-ranking-benchmark").toAbsolutePath()
        val output = RankingBenchmarkReport(Files.createTempDirectory(Files.createDirectories(outputBase), "run-"))
        println("랭킹 벤치마크 결과 경로 ${output.directory}")
        val mysql = RankingBenchmarkMySqlMetrics(container)
        val metrics = RankingBenchmarkMetrics(jdbcCounter, mysql)
        selectedSteps.filterKeys { it != RankingBenchmarkMethod.D }.values.forEach { step ->
            (step as TaskletStep).registerChunkListener(metrics)
        }
        output.environment(environment(suite, trial) + "methods=${selectedSteps.keys.joinToString(",")}")
        for (workload in workloads) {
            when {
                !workload.supportsDistribution -> output.skipped(workload, "상품별 최소 1행과 상위 1%에 80% 집중을 동시에 충족할 수 없음")
                workload !in cases -> output.skipped(workload, "선택한 실행 범위 밖")
            }
        }
        if (suite == "optimized") addCoveringIndexes()
        try {
            cases.filter { it.supportsDistribution }.forEachIndexed { index, workload ->
                val period = WeeklyRankingPeriod(WeeklyRankingScoreBenchmarkTest.PERIOD_START, 1_000L + index)
                val fixture = RankingBenchmarkFixture(jdbc, period)
                try {
                    runCase(workload, fixture, selectedSteps, launch, metrics, output, rounds)
                } finally {
                    fixture.clearResults()
                    fixture.clearInput()
                }
            }
        } finally {
            if (suite == "optimized") dropCoveringIndexes()
            output.unavailableMetrics(mysql.unavailableMetrics())
        }
    }

    private fun runCase(
        workload: RankingBenchmarkWorkload,
        fixture: RankingBenchmarkFixture,
        scoreSteps: Map<RankingBenchmarkMethod, Step>,
        launch: (RankingBenchmarkMethod, Step, String, WeeklyRankingPeriod) -> JobExecution,
        metrics: RankingBenchmarkMetrics,
        output: RankingBenchmarkReport,
        rounds: IntRange,
    ) {
        val seedNanos = measureNanoTime { fixture.seedFrom(workload) }
        val snapshotNanos = measureNanoTime { assertThat(fixture.freeze()).isEqualTo(workload.purchases) }
        output.input(workload, seedNanos, snapshotNanos)

        val methods = scoreSteps.keys.toList()
        for (round in rounds) {
            for (position in methods.indices) {
                val method = methods[(position + round) % methods.size]
                val resetNanos = measureNanoTime { fixture.clearResults() }
                val scoreStep = scoreSteps.getValue(method)
                var measurement: ScoreMeasurement? = null
                val measuredStep = object : Step by scoreStep {
                    override fun execute(stepExecution: StepExecution) {
                        measurement = metrics.measure { scoreStep.execute(stepExecution) }
                    }
                }
                val execution = launch(method, measuredStep, "run_$round", fixture.period)
                val publish = execution.stepExecutions.find { it.stepName == WeeklyRankingPublishStepConfiguration.STEP_NAME }
                val publishNanos = publish?.endTime?.let { Duration.between(publish.startTime, it).toNanos() }
                val resultRows = fixture.resultCount()
                val mismatches = if (execution.status == BatchStatus.COMPLETED) fixture.countResultMismatches() else null
                output.attempt(
                    workload,
                    method,
                    round,
                    position,
                    execution.status,
                    scoreExecution(execution, method),
                    requireNotNull(measurement),
                    resetNanos,
                    publishNanos,
                    resultRows,
                    mismatches,
                )
                assertCompleted(execution)
                assertThat(resultRows).isEqualTo(workload.products.toLong())
                assertThat(mismatches).isEqualTo(0L)
            }
        }
        output.plans(workload, explain(workload, fixture.period))
    }

    private fun explain(workload: RankingBenchmarkWorkload, period: WeeklyRankingPeriod): Map<String, List<String>> {
        val namedJdbc = NamedParameterJdbcTemplate(jdbc)
        val parameters = mapOf("snapshotId" to period.snapshotId)
        val purchasePaging = purchasePagingQueryProvider().apply { init(dataSource) }
        val scorePaging = scorePagingQueryProvider().apply { init(dataSource) }
        val grouped = GROUPED_SCORES_SQL.replace("?", ":snapshotId")
        val limit = minOf(workload.products, CHUNK_SIZE)
        return linkedMapOf(
            "A 첫 페이지" to plan(namedJdbc, purchasePaging.generateFirstPageQuery(CHUNK_SIZE), parameters),
            "A 후속 페이지" to laterPagePlan(purchasePaging, parameters, "purchase_id", workload.purchases / 2L),
            "B 상품 ID 조회" to plan(
                namedJdbc,
                "SELECT DISTINCT product_id FROM weekly_ranking_input WHERE snapshot_id = :snapshotId ORDER BY product_id",
                parameters,
            ),
            "B 청크 집계" to plan(namedJdbc, SCORES_BY_PRODUCT_IDS_SQL, parameters + ("productIds" to (1..limit).toList())),
            "C-Cursor 집계" to plan(namedJdbc, "$grouped ORDER BY product_id", parameters),
            "C-Paging 첫 페이지" to plan(namedJdbc, scorePaging.generateFirstPageQuery(CHUNK_SIZE), parameters),
            "C-Paging 후속 페이지" to laterPagePlan(scorePaging, parameters, "product_id", workload.products / 2L),
            "D INSERT SELECT" to plan(namedJdbc, AGGREGATE_AND_SAVE_SQL, parameters, analyze = false),
            "D 집계 SELECT" to plan(namedJdbc, grouped, parameters),
        )
    }

    private fun laterPagePlan(
        provider: PagingQueryProvider,
        parameters: Map<String, String>,
        key: String,
        value: Long,
    ): List<String> =
        plan(
            NamedParameterJdbcTemplate(jdbc),
            provider.generateRemainingPagesQuery(CHUNK_SIZE),
            parameters + (provider.getSortKeyPlaceHolder(key).removePrefix(":") to value),
        )

    private fun plan(
        namedJdbc: NamedParameterJdbcTemplate,
        sql: String,
        parameters: Map<String, Any>,
        analyze: Boolean = true,
    ): List<String> {
        val explain = if (analyze) "EXPLAIN ANALYZE" else "EXPLAIN FORMAT=JSON"
        return listOf(sql) + namedJdbc.query("$explain $sql", parameters) { row, _ -> row.getString(1) }
    }

    private fun environment(suite: String, trial: Boolean): List<String> = listOf(
        "suite=$suite",
        "feasibility_trial=$trial",
        "mysql_image=${WeeklyRankingScoreBenchmarkConfig.MYSQL_IMAGE}",
        "mysql_version=${jdbc.queryForObject("SELECT VERSION()", String::class.java)}",
        "spring_batch=${Step::class.java.getPackage().implementationVersion}",
        "jdbc_driver=${dataSource.connection.use { it.metaData.driverVersion }}",
        "jvm=${System.getProperty("java.runtime.version")}",
        "jvm_arguments=${ManagementFactory.getRuntimeMXBean().inputArguments}",
        "jvm_heap_limit_bytes=${Runtime.getRuntime().maxMemory()}",
        "jvm_processors=${Runtime.getRuntime().availableProcessors()}",
        "host_arch=${System.getProperty("os.arch")}",
        "host_os=${System.getProperty("os.name")}",
        "mysql_innodb_buffer_pool_bytes=${jdbc.queryForObject("SELECT @@innodb_buffer_pool_size", Long::class.javaObjectType)}",
        "mysql_isolation=${jdbc.queryForObject("SELECT @@transaction_isolation", String::class.java)}",
        "mysql_log_bin=${jdbc.queryForObject("SELECT @@log_bin", Int::class.javaObjectType)}",
        "pool_max=10",
        "pool_min_idle=5",
        "use_cursor_fetch=false",
        "cursor_fetch_size=driver_default",
        "paging_size=$CHUNK_SIZE",
        "chunk_size=$CHUNK_SIZE",
        "seed=${RankingBenchmarkFixture.SEED}",
        "heap_sample_ms=${RankingBenchmarkMetrics.HEAP_SAMPLE_MS}",
        "score_step_concurrency=1",
        "warmup_rounds=${if (trial) 0 else 1}",
        "measured_rounds=${if (trial) 1 else REPETITIONS}",
    )

    private fun addCoveringIndexes() {
        jdbc.execute(
            """
            ALTER TABLE weekly_ranking_input
                ADD INDEX ix_benchmark_purchase_cover (snapshot_id, purchase_id, product_id, quantity),
                ADD INDEX ix_benchmark_product_cover (snapshot_id, product_id, quantity)
            """.trimIndent(),
        )
    }

    private fun dropCoveringIndexes() {
        jdbc.execute(
            """
            ALTER TABLE weekly_ranking_input
                DROP INDEX ix_benchmark_purchase_cover,
                DROP INDEX ix_benchmark_product_cover
            """.trimIndent(),
        )
    }

    private fun workloads(): List<RankingBenchmarkWorkload> {
        val sizes = listOf(100, 1_000, 10_000, 100_000, 1_000_000).map { it to 1_000_000 } +
            listOf(10_000, 100_000, 10_000_000).map { 1_000 to it }
        return sizes.sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first }).flatMap { (products, purchases) ->
            listOf(false, true).flatMap { skewed ->
                PurchaseOrder.entries.map { order -> RankingBenchmarkWorkload(products, purchases, skewed, order) }
            }
        }
    }

    companion object {
        private const val REPETITIONS = 5
    }
}

internal class RankingBenchmarkReport(val directory: Path) {
    private val unavailable = linkedSetOf<String>()

    fun environment(values: List<String>) {
        directory.resolve("environment.txt").writeText(values.joinToString("\n", postfix = "\n"))
    }

    fun unavailableMetrics(reasons: Set<String>) {
        directory.resolve("unavailable-metrics.txt").writeText((reasons + unavailable).joinToString("\n"))
    }

    fun input(workload: RankingBenchmarkWorkload, seedNanos: Long, snapshotNanos: Long) {
        append("inputs.csv", listOf("scenario", "seed_ns", "snapshot_ns"), listOf(workload.id, seedNanos, snapshotNanos))
    }

    fun skipped(workload: RankingBenchmarkWorkload, reason: String) {
        append("skipped.csv", listOf("scenario", "reason"), listOf(workload.id, reason))
    }

    fun plans(workload: RankingBenchmarkWorkload, plans: Map<String, List<String>>) {
        val folder = Files.createDirectories(directory.resolve("explain"))
        folder.resolve("${workload.id}.txt").writeText(
            plans.entries.joinToString("\n\n", postfix = "\n") { (name, lines) -> "$name\n${lines.joinToString("\n")}" },
        )
    }

    fun attempt(
        workload: RankingBenchmarkWorkload,
        method: RankingBenchmarkMethod,
        round: Int,
        position: Int,
        jobStatus: BatchStatus,
        step: StepExecution,
        measurement: ScoreMeasurement,
        resetNanos: Long,
        publishNanos: Long?,
        resultRows: Long,
        resultMismatches: Long?,
    ) {
        if (measurement.appCpuNanos == null) unavailable.add("JVM 프로세스 CPU - 사용 가능한 누적 CPU 카운터가 없음")
        val statements = measurement.mysql.statements
        val scoringSql = statements?.filter { sqlCategory(it.sql) == "scoring" }
        val measured = linkedMapOf(
            "scenario" to workload.id,
            "method" to method.name,
            "round" to round,
            "warmup" to (round == 0),
            "order" to position + 1,
            "status" to jobStatus.name,
            "score_status" to step.status.name,
            "products" to workload.products,
            "purchases" to workload.purchases,
            "score_ns" to measurement.elapsedNanos,
            "purchases_per_second" to workload.purchases * 1_000_000_000.0 / measurement.elapsedNanos,
            "reset_ns" to resetNanos,
            "publish_ns" to publishNanos,
            "read_count" to step.readCount,
            "write_count" to step.writeCount,
            "commit_count" to step.commitCount,
            "rollback_count" to step.rollbackCount,
            "data_chunks" to measurement.dataChunks.takeUnless { method == RankingBenchmarkMethod.D },
            "chunk_mean_ns" to measurement.meanChunkNanos,
            "chunk_p95_ns" to measurement.p95ChunkNanos,
            "chunk_max_ns" to measurement.maxChunkNanos,
            "result_rows" to resultRows,
            "result_mismatches" to resultMismatches,
            "jdbc_scoring_calls" to (measurement.jdbcCalls["scoring"]?.executions ?: 0L),
            "jdbc_scoring_batches" to (measurement.jdbcCalls["scoring"]?.batches ?: 0L),
            "jdbc_metadata_calls" to (measurement.jdbcCalls["batch_metadata"]?.executions ?: 0L),
            "jdbc_metadata_batches" to (measurement.jdbcCalls["batch_metadata"]?.batches ?: 0L),
            "mysql_scoring_selects" to scoringSql?.filter { it.operation == "SELECT" }?.sumOf { it.count },
            "mysql_scoring_writes" to scoringSql?.filter { it.operation in WRITE_OPERATIONS }?.sumOf { it.count },
            "mysql_metadata_statements" to statements?.filter { sqlCategory(it.sql) == "batch_metadata" }?.sumOf { it.count },
            "heap_peak_sample_bytes" to measurement.peakObservedHeapBytes,
            "gc_count" to measurement.gcCount,
            "gc_ms" to measurement.gcMillis,
            "pool_connections_used" to measurement.connectionUsage.closedCheckouts,
            "pool_longest_checkout_ms" to measurement.connectionUsage.longestMillis,
            "app_cpu_ns" to measurement.appCpuNanos,
            "mysql_sql_cpu_ps" to statements?.filter { sqlCategory(it.sql) != "other" }
                ?.takeIf { rows -> rows.isNotEmpty() && rows.all { it.cpuPicoseconds != null } }
                ?.sumOf { requireNotNull(it.cpuPicoseconds) },
            "mysql_received_bytes" to measurement.mysql.bytesReceived,
            "mysql_sent_bytes" to measurement.mysql.bytesSent,
            "innodb_file_read_bytes" to measurement.mysql.fileReadBytes,
            "innodb_file_write_bytes" to measurement.mysql.fileWriteBytes,
        )
        append("runs.csv", measured.keys.toList(), measured.values.toList())
        statements?.forEach { statement ->
            append(
                "sql.csv",
                SQL_COLUMNS,
                listOf(
                    workload.id, method.name, round, sqlCategory(statement.sql), statement.operation, statement.digest,
                    statement.count, statement.elapsedPicoseconds, statement.rowsExamined, statement.rowsSent,
                    statement.rowsAffected, statement.cpuPicoseconds, statement.sql,
                ),
            )
        }
    }

    private fun append(file: String, header: List<String>, row: List<Any?>) {
        val path = directory.resolve(file)
        if (!Files.exists(path)) Files.writeString(path, header.joinToString(",", postfix = "\n"))
        val line = row.joinToString(",", postfix = "\n") { value ->
            value?.toString()?.let { "\"${it.replace("\"", "\"\"")}\"" }.orEmpty()
        }
        Files.writeString(path, line, java.nio.file.StandardOpenOption.APPEND)
    }

    companion object {
        private val WRITE_OPERATIONS = setOf("INSERT", "UPDATE", "UPSERT")
        private val SQL_COLUMNS = listOf(
            "scenario", "method", "round", "category", "operation", "digest", "executions", "elapsed_ps", "rows_examined",
            "rows_sent", "rows_affected", "cpu_ps", "sql",
        )
    }
}
