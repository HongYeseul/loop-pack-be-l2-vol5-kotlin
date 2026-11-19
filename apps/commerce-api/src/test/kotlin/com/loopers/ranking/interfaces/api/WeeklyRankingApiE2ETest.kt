package com.loopers.ranking.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.jdbc.Sql
import java.time.LocalDate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql("/db/weekly-ranking.sql")
class WeeklyRankingApiE2ETest @Autowired constructor(
    private val http: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) {
    private val snapshots = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        snapshots.forEach { snapshotId ->
            jdbc.update("DELETE FROM weekly_ranking_result WHERE snapshot_id = ?", snapshotId)
        }
    }

    @Test
    fun `요청한 주의 가장 높은 리비전을 순위순으로 제한한다`() {
        snapshot(REQUESTED_WEEK, 10, Row(21, 3, 3), Row(23, 8, 2), Row(22, 13, 1))
        snapshot(REQUESTED_WEEK, 11, Row(100, 100, null))
        snapshot(REQUESTED_WEEK, 9, Row(99, 50, 1))
        snapshot(REQUESTED_WEEK.minusWeeks(1), 20, Row(100, 30, 1))
        snapshot(REQUESTED_WEEK.plusWeeks(1), 30, Row(100, 50, 1))

        val items = getItems("?periodStart=$REQUESTED_WEEK&limit=2")

        assertThat(items.map { it.path("productId").asLong() }).containsExactly(22L, 23L)
        assertThat(items.map { it.path("score").asLong() }).containsExactly(13L, 8L)
        assertThat(items.map { it.path("rank").asLong() }).containsExactly(1L, 2L)
        assertThat(items.map { it.path("periodStart").asText() }).containsOnly(REQUESTED_WEEK.toString())
    }

    @Test
    fun `요청한 주에 순위가 없으면 직전 주 결과와 해당 주의 시작일을 반환한다`() {
        val previousWeek = REQUESTED_WEEK.minusWeeks(1)
        snapshot(previousWeek, 9, Row(99, 30, 1))
        snapshot(previousWeek, 10, Row(7, 6, 1))
        snapshot(REQUESTED_WEEK, 1, Row(99, 50, null))

        val item = getItems("?periodStart=$REQUESTED_WEEK&limit=100").single()

        assertThat(item.path("periodStart").asText()).isEqualTo(previousWeek.toString())
        assertThat(item.path("productId").asLong()).isEqualTo(7L)
        assertThat(item.path("score").asLong()).isEqualTo(6L)
        assertThat(item.path("rank").asLong()).isEqualTo(1L)
    }

    @Test
    fun `요청한 주와 직전 주에 순위가 없으면 빈 배열을 반환한다`() {
        snapshot(REQUESTED_WEEK.minusWeeks(2), 1, Row(7, 30, 1))

        val items = getItems("?periodStart=$REQUESTED_WEEK")

        assertThat(items.isArray).isTrue()
        assertThat(items.size()).isZero()
    }

    @Test
    fun `limit을 생략하면 열 건을 반환한다`() {
        val rows = (1L..11L).map { rank -> Row(rank, 12 - rank, rank) }
        snapshot(REQUESTED_WEEK, 1, *rows.toTypedArray())

        val items = getItems("?periodStart=$REQUESTED_WEEK")

        assertThat(items.map { it.path("rank").asLong() }).containsExactlyElementsOf(1L..10L)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "?periodStart=2026-8-17",
            "?periodStart=2026-02-30",
            "?periodStart=2026-08-18",
            "?periodStart=2026-08-17&limit=",
            "?periodStart=2026-08-17&limit=0",
            "?periodStart=2026-08-17&limit=101",
            "?periodStart=2026-08-17&limit=abc",
        ],
    )
    fun `잘못된 요청은 400으로 응답한다`(query: String) {
        val response = http.getForEntity("$ENDPOINT$query", JsonNode::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.path("meta")?.path("errorCode")?.asText()).isEqualTo(ErrorType.BAD_REQUEST.code)
    }

    private fun getItems(query: String): JsonNode {
        val response = http.getForEntity("$ENDPOINT$query", JsonNode::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return requireNotNull(response.body).path("data")
    }

    private fun snapshot(periodStart: LocalDate, revision: Long, vararg rows: Row) {
        val snapshotId = "weekly-$periodStart-r$revision"
        snapshots.add(snapshotId)

        rows.forEach {
            jdbc.update(
                "INSERT INTO weekly_ranking_result (snapshot_id, product_id, score, rank_position) VALUES (?, ?, ?, ?)",
                snapshotId,
                it.productId,
                it.score,
                it.rank,
            )
        }
    }

    private data class Row(val productId: Long, val score: Long, val rank: Long?)

    private companion object {
        const val ENDPOINT = "/api/v1/rankings/weekly"
        val REQUESTED_WEEK: LocalDate = LocalDate.of(2026, 8, 17)
    }
}
