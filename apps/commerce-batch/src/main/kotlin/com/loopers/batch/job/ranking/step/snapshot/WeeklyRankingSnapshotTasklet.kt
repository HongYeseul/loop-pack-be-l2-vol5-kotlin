package com.loopers.batch.job.ranking.step.snapshot

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingSnapshotTasklet(private val jdbc: JdbcTemplate) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val step = contribution.stepExecution
        val period = WeeklyRankingPeriod.from(step.jobParameters)
        val context = step.executionContext
        val hasInput = hasFrozenInput(period.snapshotId)
        if (hasInput || context.containsKey(INPUT_FROZEN)) {
            if (!hasInput) contribution.exitStatus = ExitStatus(NO_INPUT_EXIT_CODE)
            return RepeatStatus.FINISHED
        }

        deletePublishedInputs()
        val count = jdbc.update(
            """
            INSERT INTO weekly_ranking_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
            SELECT ?, order_id, product_id, quantity, confirmed_at
            FROM purchase_activity
            WHERE confirmed_at >= ? AND confirmed_at < ?
            """.trimIndent(),
            period.snapshotId,
            period.start.atStartOfDay(),
            period.start.plusWeeks(1).atStartOfDay(),
        )
        // 구매가 0건이어도 재시작할 때 같은 입력을 사용하도록 고정 여부를 저장
        context.putString(INPUT_FROZEN, "true")
        contribution.incrementWriteCount(count.toLong())
        if (count == 0) contribution.exitStatus = ExitStatus(NO_INPUT_EXIT_CODE)
        return RepeatStatus.FINISHED
    }

    private fun hasFrozenInput(snapshotId: String): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM weekly_ranking_input WHERE snapshot_id = ?)",
            Boolean::class.javaObjectType,
            snapshotId,
        ) == true

    private fun deletePublishedInputs() {
        jdbc.update(
            """
            DELETE frozen
            FROM weekly_ranking_input AS frozen
            JOIN weekly_ranking_result AS published USING (snapshot_id)
            WHERE published.rank_position = 1
            """.trimIndent(),
        )
    }

    companion object {
        const val NO_INPUT_EXIT_CODE = "COMPLETED_NO_INPUT"
        private const val INPUT_FROZEN = "weeklyRankingInputFrozen"
    }
}
