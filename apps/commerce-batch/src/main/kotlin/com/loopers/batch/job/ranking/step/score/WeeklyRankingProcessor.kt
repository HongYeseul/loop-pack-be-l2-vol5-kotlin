package com.loopers.batch.job.ranking.step.score

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.ItemProcessor
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingProcessor(
    private val jdbc: JdbcTemplate,
    @Value("#{stepExecution.jobParameters}") parameters: JobParameters,
) : ItemProcessor<Long, WeeklyProductScore> {
    private val snapshotId = WeeklyRankingPeriod.from(parameters).snapshotId

    override fun process(productId: Long): WeeklyProductScore {
        val score = requireNotNull(
            jdbc.queryForObject(
                "SELECT SUM(quantity) FROM weekly_ranking_input WHERE snapshot_id = ? AND product_id = ?",
                Long::class.java,
                snapshotId,
                productId,
            ),
        )
        return WeeklyProductScore(snapshotId, productId, score)
    }
}
