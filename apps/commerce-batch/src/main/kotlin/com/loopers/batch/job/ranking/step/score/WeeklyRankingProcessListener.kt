package com.loopers.batch.job.ranking.step.score

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ItemProcessListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklyRankingJobConfig.JOB_NAME)
class WeeklyRankingProcessListener : ItemProcessListener<Long, WeeklyProductScore> {
    private val log = LoggerFactory.getLogger(WeeklyRankingProcessListener::class.java)

    override fun onProcessError(item: Long, e: Exception) {
        log.error("상품 점수 처리 실패: 상품 ID={}", item, e)
    }
}
