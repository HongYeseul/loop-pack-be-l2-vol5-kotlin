package com.loopers.job.ranking.benchmark

import com.zaxxer.hikari.metrics.IMetricsTracker
import com.sun.management.OperatingSystemMXBean
import net.ttddyy.dsproxy.ExecutionInfo
import net.ttddyy.dsproxy.QueryInfo
import net.ttddyy.dsproxy.listener.QueryExecutionListener
import org.springframework.batch.core.ChunkListener
import org.springframework.batch.core.scope.context.ChunkContext
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

class BenchmarkJdbcCounter : QueryExecutionListener, IMetricsTracker {
    internal var counts: MutableMap<String, JdbcCalls>? = null
    internal var connectionUsage: BenchmarkConnectionUsage? = null

    override fun beforeQuery(execution: ExecutionInfo, queries: List<QueryInfo>) = Unit

    override fun afterQuery(execution: ExecutionInfo, queries: List<QueryInfo>) {
        val active = counts ?: return
        val category = queries.map { sqlCategory(it.query) }.distinct().singleOrNull() ?: "other"
        val calls = active.getOrPut(category) { JdbcCalls() }
        calls.executions++
        if (execution.isBatch) calls.batches++
    }

    override fun recordConnectionUsageMillis(elapsedBorrowedMillis: Long) {
        val usage = connectionUsage ?: return
        usage.closedCheckouts++
        usage.longestMillis = maxOf(usage.longestMillis, elapsedBorrowedMillis)
    }
}

internal data class JdbcCalls(var executions: Long = 0, var batches: Long = 0)

internal data class BenchmarkConnectionUsage(var closedCheckouts: Long = 0, var longestMillis: Long = 0)

private val SCORING_TABLE = Regex("weekly_ranking_(input|result)", RegexOption.IGNORE_CASE)

internal fun sqlCategory(sql: String): String = when {
    sql.contains("BATCH_", ignoreCase = true) -> "batch_metadata"
    SCORING_TABLE.containsMatchIn(sql) -> "scoring"
    else -> "other"
}

internal class RankingBenchmarkMetrics(
    private val jdbc: BenchmarkJdbcCounter,
    private val mysql: RankingBenchmarkMySqlMetrics,
) : ChunkListener {
    private var chunks: MutableList<Long>? = null
    private var chunkStart = 0L
    private var readBeforeChunk = 0L

    override fun beforeChunk(context: ChunkContext) {
        if (chunks == null) return
        readBeforeChunk = context.stepContext.stepExecution.readCount
        chunkStart = System.nanoTime()
    }

    override fun afterChunk(context: ChunkContext) {
        val samples = chunks ?: return
        if (context.stepContext.stepExecution.readCount > readBeforeChunk) {
            samples.add(System.nanoTime() - chunkStart)
        }
    }

    fun measure(action: () -> Unit): ScoreMeasurement {
        val dbBefore = mysql.snapshot()
        val jvmBefore = JvmCounters.read()
        val memory = ManagementFactory.getMemoryMXBean()
        val peakHeap = AtomicLong(memory.heapMemoryUsage.used)
        val sampler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ranking-benchmark-heap").apply { isDaemon = true }
        }
        sampler.scheduleAtFixedRate(
            { peakHeap.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf) },
            HEAP_SAMPLE_MS,
            HEAP_SAMPLE_MS,
            TimeUnit.MILLISECONDS,
        )
        val chunkTimes = mutableListOf<Long>()
        val calls = mutableMapOf<String, JdbcCalls>()
        val connectionUsage = BenchmarkConnectionUsage()
        chunks = chunkTimes
        jdbc.counts = calls
        jdbc.connectionUsage = connectionUsage
        val start = System.nanoTime()
        val elapsed = try {
            action()
            System.nanoTime() - start
        } finally {
            jdbc.counts = null
            jdbc.connectionUsage = null
            chunks = null
            sampler.shutdownNow()
        }

        val jvmAfter = JvmCounters.read()
        peakHeap.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
        val db = mysql.snapshot().delta(dbBefore)
        return ScoreMeasurement(
            elapsed,
            chunkTimes.size,
            chunkTimes.takeIf { it.isNotEmpty() }?.average(),
            chunkTimes.takeIf { it.isNotEmpty() }?.let { it.sorted()[ceil(it.size * 0.95).toInt() - 1] },
            chunkTimes.maxOrNull(),
            calls,
            peakHeap.get(),
            jvmAfter.gcCount - jvmBefore.gcCount,
            jvmAfter.gcMillis - jvmBefore.gcMillis,
            connectionUsage,
            subtractCounters(jvmAfter.cpuNanos, jvmBefore.cpuNanos),
            db,
        )
    }

    private data class JvmCounters(val gcCount: Long, val gcMillis: Long, val cpuNanos: Long?) {
        companion object {
            fun read(): JvmCounters {
                val gc = ManagementFactory.getGarbageCollectorMXBeans()
                val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
                return JvmCounters(
                    gc.sumOf { it.collectionCount.coerceAtLeast(0) },
                    gc.sumOf { it.collectionTime.coerceAtLeast(0) },
                    os?.processCpuTime?.takeIf { it >= 0 },
                )
            }
        }
    }

    companion object {
        const val HEAP_SAMPLE_MS = 50L
    }
}

internal data class ScoreMeasurement(
    val elapsedNanos: Long,
    val dataChunks: Int,
    val meanChunkNanos: Double?,
    val p95ChunkNanos: Long?,
    val maxChunkNanos: Long?,
    val jdbcCalls: Map<String, JdbcCalls>,
    val peakObservedHeapBytes: Long,
    val gcCount: Long,
    val gcMillis: Long,
    val connectionUsage: BenchmarkConnectionUsage,
    val appCpuNanos: Long?,
    val mysql: MySqlScoreCounters,
)

internal fun subtractCounters(after: Long?, before: Long?): Long? =
    if (after != null && before != null && after >= before) after - before else null
