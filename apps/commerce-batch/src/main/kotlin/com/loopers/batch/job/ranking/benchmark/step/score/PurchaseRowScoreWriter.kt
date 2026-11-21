package com.loopers.batch.job.ranking.benchmark.step.score

import com.loopers.batch.job.ranking.step.score.WeeklyProductScore
import org.springframework.jdbc.core.JdbcTemplate

class PurchaseRowScoreWriter(private val jdbc: JdbcTemplate) {
    fun write(scores: List<WeeklyProductScore>) {
        scores.forEach { item ->
            jdbc.update(
                "INSERT INTO weekly_ranking_result (snapshot_id, product_id, score) VALUES (?, ?, ?) AS candidate " +
                    "ON DUPLICATE KEY UPDATE score = weekly_ranking_result.score + candidate.score",
                item.snapshotId,
                item.productId,
                item.score,
            )
        }
    }
}
