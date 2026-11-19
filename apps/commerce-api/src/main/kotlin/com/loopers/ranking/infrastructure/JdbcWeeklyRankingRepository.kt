package com.loopers.ranking.infrastructure

import com.loopers.ranking.domain.WeeklyRankingItem
import com.loopers.ranking.domain.WeeklyRankingRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class JdbcWeeklyRankingRepository(
    private val jdbc: JdbcTemplate,
) : WeeklyRankingRepository {
    override fun findRankedItems(periodStart: LocalDate, previousPeriodStart: LocalDate, limit: Int): List<WeeklyRankingItem> {
        val requestedWeek = "weekly-$periodStart-r%"
        val previousWeek = "weekly-$previousPeriodStart-r%"

        // 배치 WeeklyRankingPeriod의 snapshot_id 형식 변경 시 기간과 리비전 추출도 수정
        return jdbc.query(
            """
            SELECT result.snapshot_id, result.product_id, result.score, result.rank_position
            FROM weekly_ranking_result AS result
            JOIN (
                SELECT snapshot_id
                FROM weekly_ranking_result
                WHERE (snapshot_id LIKE ? OR snapshot_id LIKE ?)
                  AND rank_position IS NOT NULL
                GROUP BY snapshot_id
                ORDER BY (snapshot_id LIKE ?) DESC,
                         CAST(SUBSTRING_INDEX(snapshot_id, '-r', -1) AS UNSIGNED) DESC
                LIMIT 1
            ) AS selected USING (snapshot_id)
            WHERE result.rank_position IS NOT NULL
            ORDER BY result.rank_position
            LIMIT ?
            """.trimIndent(),
            { row, _ ->
                val snapshotId = row.getString("snapshot_id")
                WeeklyRankingItem(
                    periodStart = LocalDate.parse(snapshotId.removePrefix("weekly-").substringBeforeLast("-r")),
                    productId = row.getLong("product_id"),
                    score = row.getLong("score"),
                    rank = row.getLong("rank_position"),
                )
            },
            requestedWeek,
            previousWeek,
            requestedWeek,
            limit,
        )
    }
}
