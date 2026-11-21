package com.loopers.job.ranking.benchmark

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer

internal class RankingBenchmarkMySqlMetrics(private val container: MySQLContainer<*>) {
    private val admin = JdbcTemplate(
        DriverManagerDataSource(
            container.jdbcUrl.replace("/${container.databaseName}", "/performance_schema"),
            "root",
            container.password,
        ),
    )
    private val errors = linkedSetOf<String>()
    private val cpuEnabled = observed("MySQL statement CPU") {
        admin.update("UPDATE performance_schema.setup_consumers SET ENABLED = 'YES' WHERE NAME = 'events_statements_cpu'")
        val enabled = admin.queryForObject(
            "SELECT ENABLED FROM performance_schema.setup_consumers WHERE NAME = 'events_statements_cpu'",
            String::class.java,
        )
        check(enabled == "YES") { "events_statements_cpu 비활성" }
        val cpuTimer = admin.queryForObject(
            "SELECT TIMER_FREQUENCY FROM performance_schema.performance_timers WHERE TIMER_NAME = 'THREAD_CPU'",
            Long::class.javaObjectType,
        )
        check(cpuTimer != null && cpuTimer > 0L) { "THREAD_CPU 타이머 미지원" }
    } != null

    fun snapshot(): MySqlScoreCounters {
        val statements = observed("MySQL SQL digest") {
            val cpu = if (cpuEnabled) "SUM_CPU_TIME" else "NULL"
            admin.query(
                """
                SELECT DIGEST, DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT, SUM_ROWS_EXAMINED,
                    SUM_ROWS_SENT, SUM_ROWS_AFFECTED, $cpu AS CPU_PICOSECONDS
                FROM performance_schema.events_statements_summary_by_digest
                WHERE SCHEMA_NAME = ? AND DIGEST IS NOT NULL
                """.trimIndent(),
                { row, _ ->
                    MySqlStatementCounters(
                        row.getString("DIGEST"),
                        row.getString("DIGEST_TEXT"),
                        row.getLong("COUNT_STAR"),
                        row.getLong("SUM_TIMER_WAIT"),
                        row.getLong("SUM_ROWS_EXAMINED"),
                        row.getLong("SUM_ROWS_SENT"),
                        row.getLong("SUM_ROWS_AFFECTED"),
                        row.getObject("CPU_PICOSECONDS", Long::class.javaObjectType),
                    )
                },
                container.databaseName,
            )
        }
        val network = observed("MySQL 세션 바이트") {
            admin.query(
                """
                SELECT VARIABLE_NAME, VARIABLE_VALUE
                FROM performance_schema.status_by_user
                WHERE USER = ? AND VARIABLE_NAME IN ('Bytes_received', 'Bytes_sent')
                """.trimIndent(),
                { row, _ -> row.getString(1).lowercase() to row.getLong(2) },
                container.username,
            ).toMap().also { check(it.size == 2) { "벤치마크 계정의 두 네트워크 카운터를 찾을 수 없음" } }
        }
        val fileIo = observed("InnoDB 파일 I/O") {
            admin.query(
                """
                SELECT SUM(SUM_NUMBER_OF_BYTES_READ), SUM(SUM_NUMBER_OF_BYTES_WRITE)
                FROM performance_schema.file_summary_by_event_name
                WHERE EVENT_NAME LIKE 'wait/io/file/innodb/%'
                """.trimIndent(),
            ) { row, _ ->
                val readBytes = requireNotNull(row.getObject(1, Long::class.javaObjectType))
                val writeBytes = requireNotNull(row.getObject(2, Long::class.javaObjectType))
                readBytes to writeBytes
            }.single()
        }
        return MySqlScoreCounters(
            statements,
            network?.get("bytes_received"),
            network?.get("bytes_sent"),
            fileIo?.first,
            fileIo?.second,
        )
    }

    fun unavailableMetrics(): Set<String> = errors.toSet()

    private fun <T : Any> observed(label: String, query: () -> T): T? =
        try {
            query()
        } catch (exception: RuntimeException) {
            errors.add("$label - ${exception.cause?.message ?: exception.message}")
            null
        }
}

internal data class MySqlScoreCounters(
    val statements: List<MySqlStatementCounters>?,
    val bytesReceived: Long?,
    val bytesSent: Long?,
    val fileReadBytes: Long?,
    val fileWriteBytes: Long?,
) {
    fun delta(before: MySqlScoreCounters): MySqlScoreCounters {
        val earlier = before.statements?.associateBy { it.digest }
        return MySqlScoreCounters(
            if (earlier != null) statements?.mapNotNull { it.delta(earlier[it.digest]) } else null,
            subtractCounters(bytesReceived, before.bytesReceived),
            subtractCounters(bytesSent, before.bytesSent),
            subtractCounters(fileReadBytes, before.fileReadBytes),
            subtractCounters(fileWriteBytes, before.fileWriteBytes),
        )
    }
}

internal data class MySqlStatementCounters(
    val digest: String,
    val sql: String,
    val count: Long,
    val elapsedPicoseconds: Long,
    val rowsExamined: Long,
    val rowsSent: Long,
    val rowsAffected: Long,
    val cpuPicoseconds: Long?,
) {
    val operation: String
        get() = if (sql.contains("ON DUPLICATE KEY UPDATE", ignoreCase = true)) "UPSERT" else sql.substringBefore(' ').uppercase()

    fun delta(before: MySqlStatementCounters?): MySqlStatementCounters? {
        if (before == null) return takeIf { count > 0L }
        if (count <= before.count) return null
        return copy(
            count = count - before.count,
            elapsedPicoseconds = elapsedPicoseconds - before.elapsedPicoseconds,
            rowsExamined = rowsExamined - before.rowsExamined,
            rowsSent = rowsSent - before.rowsSent,
            rowsAffected = rowsAffected - before.rowsAffected,
            cpuPicoseconds = subtractCounters(cpuPicoseconds, before.cpuPicoseconds),
        )
    }
}
