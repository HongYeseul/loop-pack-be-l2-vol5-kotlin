package com.loopers.batch.job.ranking.benchmark.step.score

import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class SqlWeeklyRankingScoreTasklet(private val jdbc: NamedParameterJdbcTemplate) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val snapshotId = WeeklyRankingPeriod.from(contribution.stepExecution.jobParameters).snapshotId
        jdbc.update(AGGREGATE_AND_SAVE_SQL, mapOf("snapshotId" to snapshotId))
        return RepeatStatus.FINISHED
    }

    companion object {
        internal val AGGREGATE_AND_SAVE_SQL = """
            INSERT INTO weekly_ranking_result (snapshot_id, product_id, score)
            SELECT :snapshotId, grouped_scores.product_id, grouped_scores.score
            FROM (
                SELECT product_id, SUM(quantity) AS score
                FROM weekly_ranking_input
                WHERE snapshot_id = :snapshotId
                GROUP BY product_id
            ) AS grouped_scores
            ON DUPLICATE KEY UPDATE score = grouped_scores.score
        """.trimIndent()
    }
}
