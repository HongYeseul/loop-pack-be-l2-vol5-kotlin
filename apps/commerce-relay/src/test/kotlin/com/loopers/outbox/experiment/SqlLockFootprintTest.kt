package com.loopers.outbox.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** 선점 SQL 이 LIMIT 과 별개로 몇 행을 잠그는지, 결과는 build/reports/week7/sql-lock-footprint.json */
@SpringBootTest
@DisplayName("선점 SQL 별 실행 계획과 잠그는 행")
class SqlLockFootprintTest @Autowired constructor(
    private val entityManager: EntityManager,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    environment: Environment,
) {
    companion object {
        private const val LIMIT = 10
        private const val PUBLISHED_HISTORY = 200
        private const val PENDING_ORDERS = 50

        private const val CLAIMABLE = """
            ((o.status = 'PENDING' AND o.next_attempt_at <= UTC_TIMESTAMP(6))
              OR (o.status = 'CLAIMED' AND o.claimed_until < UTC_TIMESTAMP(6)))
        """
        private const val HEAD_OF_KEY = """
            NOT EXISTS (
              SELECT 1 FROM outbox p
              WHERE p.aggregate_type = o.aggregate_type AND p.aggregate_id = o.aggregate_id
                AND p.id < o.id AND p.status IN ('PENDING', 'CLAIMED', 'FAILED')
            )
        """
        private const val SELECT_AND_LOCK = "SELECT o.* FROM outbox o WHERE $CLAIMABLE AND $HEAD_OF_KEY ORDER BY o.id LIMIT $LIMIT FOR UPDATE SKIP LOCKED"
        private const val PENDING_ONLY =
            "SELECT o.* FROM outbox o WHERE o.status = 'PENDING' AND $HEAD_OF_KEY ORDER BY o.id LIMIT $LIMIT FOR UPDATE SKIP LOCKED"
        private const val PICK_IDS = "SELECT o.id FROM outbox o WHERE $CLAIMABLE AND $HEAD_OF_KEY ORDER BY o.id LIMIT $LIMIT"
        private const val LOCK_PICKED = "SELECT o.* FROM outbox o WHERE o.id IN (:ids) AND $CLAIMABLE FOR UPDATE SKIP LOCKED"
        private const val CANDIDATES = LIMIT * 3
        private const val PICK_CANDIDATE_IDS = "SELECT o.id FROM outbox o WHERE $CLAIMABLE AND $HEAD_OF_KEY ORDER BY o.id LIMIT $CANDIDATES"
        private const val LOCK_WITHIN_CANDIDATES =
            "SELECT o.* FROM outbox o WHERE o.id IN (:ids) AND $CLAIMABLE ORDER BY o.id LIMIT $LIMIT FOR UPDATE SKIP LOCKED"
        private const val MARK_BY_IDS = "UPDATE outbox SET status = 'PUBLISHED' WHERE id IN (:ids) AND status = 'PENDING'"
    }

    private val observer = LockObserver(environment)

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @Test
    @DisplayName("고르면서 잠그면 LIMIT 보다 많은 행을 잠그고, 고른 뒤 id 로 잠그면 고른 행만 잠근다")
    fun measuresLockFootprint() {
        insert(status = "PUBLISHED", orders = 1..PUBLISHED_HISTORY, rowsPerOrder = 1, prefix = "history")
        insert(status = "PENDING", orders = 1..PENDING_ORDERS, rowsPerOrder = 2, prefix = "pending")
        val updateBeforeAnalyze = explain(MARK_BY_IDS.replace(":ids", pickedIds().joinToString(",")))
        jdbcTemplate.execute("ANALYZE TABLE outbox")
        val updateAfterAnalyze = explain(MARK_BY_IDS.replace(":ids", pickedIds().joinToString(",")))

        val variants = listOf(
            measure("한 문장으로 고르면서 잠그기", SELECT_AND_LOCK, SELECT_AND_LOCK) {
                entityManager.createNativeQuery(SELECT_AND_LOCK).resultList.size
            },
            measure("한 문장으로 고르면서 잠그기, status 조건만 (② 실험)", PENDING_ONLY, PENDING_ONLY) {
                entityManager.createNativeQuery(PENDING_ONLY).resultList.size
            },
            measure("잠그지 않고 고른 뒤 id 로 잠그기", "$PICK_IDS\n$LOCK_PICKED", LOCK_PICKED.replace(":ids", pickedIds().joinToString(","))) {
                entityManager.createNativeQuery(LOCK_PICKED).setParameter("ids", pickedIds()).resultList.size
            },
            measure(
                "후보를 넉넉히 고른 뒤 그 안에서 건너뛰며 잠그기 (지금 선점)",
                "$PICK_CANDIDATE_IDS\n$LOCK_WITHIN_CANDIDATES",
                LOCK_WITHIN_CANDIDATES.replace(":ids", candidateIds().joinToString(",")),
            ) {
                entityManager.createNativeQuery(LOCK_WITHIN_CANDIDATES).setParameter("ids", candidateIds()).resultList.size
            },
        )
        saveEvidence(variants, updateBeforeAnalyze, updateAfterAnalyze)

        val selectAndLock = variants.first()
        assertThat(selectAndLock.byIsolation.getValue("REPEATABLE READ").recordLocks).isGreaterThan(LIMIT)
        assertThat(variants.takeLast(2).flatMap { it.byIsolation.values }).allSatisfy {
            assertThat(it.returned).isEqualTo(LIMIT)
            assertThat(it.recordLocks).isEqualTo(LIMIT)
            assertThat(it.locksByIndexAndMode).isEqualTo(mapOf("PRIMARY X,REC_NOT_GAP" to LIMIT))
        }
    }

    private fun measure(name: String, sql: String, explainSql: String, run: () -> Int): Variant {
        val byIsolation = linkedMapOf(
            "REPEATABLE READ" to TransactionDefinition.ISOLATION_REPEATABLE_READ,
            "READ COMMITTED" to TransactionDefinition.ISOLATION_READ_COMMITTED,
        ).mapValues { (_, isolation) ->
            requireNotNull(
                TransactionTemplate(transactionManager).apply { isolationLevel = isolation }.execute { status ->
                    val returned = run()
                    val locks = observer.locks().filter { it["LOCK_TYPE"] == "RECORD" }
                    status.setRollbackOnly()
                    Footprint(
                        returned = returned,
                        recordLocks = locks.size,
                        locksByIndexAndMode = locks.groupingBy { "${it["INDEX_NAME"]} ${it["LOCK_MODE"]}" }.eachCount(),
                        lockedPrimaryKeys = ranges(locks.filter { it["INDEX_NAME"] == "PRIMARY" }.mapNotNull { it["LOCK_DATA"] }),
                    )
                },
            )
        }
        return Variant(name, sql.trimIndent(), explain(explainSql), byIsolation)
    }

    private fun pickedIds(): List<Long> = jdbcTemplate.queryForList(PICK_IDS, Long::class.java)

    private fun candidateIds(): List<Long> = jdbcTemplate.queryForList(PICK_CANDIDATE_IDS, Long::class.java)

    private fun explain(sql: String): List<String> = jdbcTemplate.queryForList("EXPLAIN $sql").map { row ->
        "table=${row["table"]} type=${row["type"]} key=${row["key"]} rows=${row["rows"]} extra=${row["Extra"]}"
    }

    // 1,2,3,5 → "1-3, 5" 처럼 줄여 사람이 읽기 쉽게
    private fun ranges(keys: List<String>): List<String> {
        val numbers = keys.mapNotNull { it.toLongOrNull() }.sorted()
        val grouped = mutableListOf<String>()
        var start: Long? = null
        var previous: Long? = null
        for (number in numbers) {
            if (start == null || previous == null || number != previous + 1) {
                start?.let { grouped += if (it == previous) "$it" else "$it-$previous" }
                start = number
            }
            previous = number
        }
        start?.let { grouped += if (it == previous) "$it" else "$it-$previous" }
        return grouped + keys.filter { it.toLongOrNull() == null }
    }

    private fun insert(status: String, orders: IntRange, rowsPerOrder: Int, prefix: String) {
        for (order in orders) {
            repeat(rowsPerOrder) { sequence ->
                jdbcTemplate.update(
                    """
                    INSERT INTO outbox (created_at, updated_at, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                      topic, payload, status, attempt_count, next_attempt_at)
                    VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, 'ORDER', ?, 'OrderConfirmed', 1, 'order-events', '{}', ?, 0,
                      UTC_TIMESTAMP(6) - INTERVAL 1 SECOND)
                    """,
                    "$prefix-$order-$sequence",
                    "$prefix-$order",
                    status,
                )
            }
        }
    }

    private fun saveEvidence(variants: List<Variant>, updateBeforeAnalyze: List<String>, updateAfterAnalyze: List<String>) {
        val directory = Path.of("build", "reports", "week7")
        Files.createDirectories(directory)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            directory.resolve("sql-lock-footprint.json").toFile(),
            mapOf(
                "observedAt" to Instant.now().toString(),
                "data" to mapOf(
                    "publishedHistoryIds" to "1-$PUBLISHED_HISTORY",
                    "pendingIds" to "${PUBLISHED_HISTORY + 1}-${PUBLISHED_HISTORY + PENDING_ORDERS * 2}",
                    "pendingRowsPerOrder" to 2,
                    "limit" to LIMIT,
                    "candidates" to CANDIDATES,
                ),
                "variants" to variants,
                "markPublishedUpdate" to mapOf(
                    "sql" to MARK_BY_IDS,
                    "planBeforeAnalyze" to updateBeforeAnalyze,
                    "planAfterAnalyze" to updateAfterAnalyze,
                ),
            ),
        )
    }

    data class Variant(val name: String, val sql: String, val plan: List<String>, val byIsolation: Map<String, Footprint>)

    data class Footprint(
        val returned: Int,
        val recordLocks: Int,
        val locksByIndexAndMode: Map<String, Int>,
        val lockedPrimaryKeys: List<String>,
    )
}
