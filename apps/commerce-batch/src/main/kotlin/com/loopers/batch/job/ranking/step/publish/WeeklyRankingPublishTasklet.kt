package com.loopers.batch.job.ranking.step.publish

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingPublishTasklet(private val jdbc: JdbcTemplate) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val snapshotId = WeeklyRankingPeriod.from(contribution.stepExecution.jobParameters).snapshotId
        val published = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM weekly_ranking_result WHERE snapshot_id = ? AND rank_position = 1)",
            Boolean::class.javaObjectType,
            snapshotId,
        ) == true
        if (published) return RepeatStatus.FINISHED

        val expectedProducts = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT product_id) FROM weekly_ranking_input WHERE snapshot_id = ?",
            Long::class.javaObjectType,
            snapshotId,
        )
        val resultCount = jdbc.update(
            """
            UPDATE weekly_ranking_result AS result
            JOIN (
                SELECT snapshot_id, product_id, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC) AS rank_position
                FROM weekly_ranking_result
                WHERE snapshot_id = ?
            ) AS ranked USING (snapshot_id, product_id)
            SET result.rank_position = ranked.rank_position
            """.trimIndent(),
            snapshotId,
        )
        check(resultCount.toLong() == expectedProducts) { "스냅샷 ${snapshotId}의 집계 상품 수가 일치하지 않습니다" }
        contribution.incrementWriteCount(resultCount.toLong())
        return RepeatStatus.FINISHED
    }
}
