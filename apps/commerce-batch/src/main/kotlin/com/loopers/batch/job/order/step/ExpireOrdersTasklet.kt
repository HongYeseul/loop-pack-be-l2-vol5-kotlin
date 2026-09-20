package com.loopers.batch.job.order.step

import com.loopers.batch.job.order.ExpireOrdersJobConfig
import com.loopers.infrastructure.order.OrderJpaRepository
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = ExpireOrdersJobConfig.JOB_NAME)
@Component
class ExpireOrdersTasklet(
    private val orderJpaRepository: OrderJpaRepository,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val expired = orderJpaRepository.expireDrafts(ZonedDateTime.now())
        // 몇 건을 바꿨는지 실행 기록에 남긴다
        contribution.incrementWriteCount(expired.toLong())
        return RepeatStatus.FINISHED
    }
}
