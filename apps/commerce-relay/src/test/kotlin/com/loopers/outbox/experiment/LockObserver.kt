package com.loopers.outbox.experiment

import org.springframework.core.env.Environment
import java.sql.DriverManager
import java.sql.ResultSet

// 앱 계정은 performance_schema 조회 권한이 없어 root 로 연결
class LockObserver(private val environment: Environment) {
    fun lockedPrimaryKeys(): List<String> = query(
        """
        SELECT LOCK_DATA FROM performance_schema.data_locks
        WHERE OBJECT_NAME = 'outbox' AND INDEX_NAME = 'PRIMARY' AND LOCK_TYPE = 'RECORD' AND LOCK_MODE LIKE 'X%'
        """,
    ) { it.getString(1) }

    fun transactionsHoldingLocks(): Int =
        query("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_rows_locked > 0") { it.getInt(1) }.single()

    fun locks(): List<Map<String, String?>> = query(
        """
        SELECT ENGINE_TRANSACTION_ID, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
        FROM performance_schema.data_locks WHERE OBJECT_NAME = 'outbox'
        ORDER BY ENGINE_TRANSACTION_ID, INDEX_NAME, LOCK_DATA
        """,
    ) { row -> COLUMNS.associateWith { row.getString(it) } }

    fun waits(): List<Map<String, String?>> = query(
        """
        SELECT r.LOCK_MODE AS REQUESTING_LOCK_MODE, r.LOCK_DATA AS REQUESTING_LOCK_DATA,
               b.INDEX_NAME AS BLOCKING_INDEX_NAME, b.LOCK_MODE AS BLOCKING_LOCK_MODE, b.LOCK_DATA AS BLOCKING_LOCK_DATA
        FROM performance_schema.data_lock_waits w
        JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
        JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID
        """,
    ) { row -> WAIT_COLUMNS.associateWith { row.getString(it) } }

    /** 레코드 덤프는 PRIMARY 키(id)만 남기고 트랜잭션, 쿼리, 잠금 줄과 함께 */
    fun latestDeadlock(): List<String> = query("SHOW ENGINE INNODB STATUS") { it.getString("Status") }.single()
        .substringAfter("LATEST DETECTED DEADLOCK", "")
        .substringBefore("\nTRANSACTIONS\n")
        .lines()
        .map { it.trim() }
        .mapNotNull { line ->
            when {
                line.isEmpty() || line.startsWith("---") || line.startsWith("Record lock") -> null
                line.startsWith("0: ") -> describeKey(line)
                RECORD_FIELD.matches(line) -> null
                else -> line
            }
        }

    private fun describeKey(field: String): String {
        if ("supremum" in field) return "  record: supremum"
        val hex = Regex("""len 8; hex ([0-9a-f]{16})""").find(field)?.groupValues?.get(1) ?: return "  record: $field"
        return "  record: id=${java.lang.Long.parseUnsignedLong(hex, 16) xor Long.MIN_VALUE}"
    }

    fun rowLockWaits(): Long = status("Innodb_row_lock_waits")

    fun rowLockTimeMillis(): Long = status("Innodb_row_lock_time")

    private fun status(name: String): Long = query("SHOW GLOBAL STATUS LIKE '$name'") { it.getLong(2) }.single()

    private fun <T> query(sql: String, read: (ResultSet) -> T): List<T> =
        DriverManager.getConnection(
            environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url"),
            "root",
            environment.getRequiredProperty("datasource.mysql-jpa.main.password"),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
        }

    companion object {
        private val RECORD_FIELD = Regex("""^\d+: (len \d+|SQL NULL).*""")
        private val COLUMNS = listOf("ENGINE_TRANSACTION_ID", "INDEX_NAME", "LOCK_TYPE", "LOCK_MODE", "LOCK_STATUS", "LOCK_DATA")
        private val WAIT_COLUMNS = listOf(
            "REQUESTING_LOCK_MODE",
            "REQUESTING_LOCK_DATA",
            "BLOCKING_INDEX_NAME",
            "BLOCKING_LOCK_MODE",
            "BLOCKING_LOCK_DATA",
        )
    }
}
