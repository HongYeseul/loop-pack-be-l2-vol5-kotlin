package com.loopers.batch.job.ranking.step.score

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingWriter(
    private val jdbc: JdbcTemplate,
) : ItemWriter<WeeklyProductScore> {
    override fun write(items: Chunk<out WeeklyProductScore>) {
        items.forEach { item ->
            jdbc.update(
                "INSERT INTO weekly_ranking_result (snapshot_id, product_id, score) VALUES (?, ?, ?) AS candidate " +
                    "ON DUPLICATE KEY UPDATE score = candidate.score",
                item.snapshotId,
                item.productId,
                item.score,
            )
        }
    }
}
