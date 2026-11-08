package com.loopers.batch.job.outbox.step

import com.loopers.batch.job.outbox.BackfillOrderConfirmedOutboxJobConfig
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = BackfillOrderConfirmedOutboxJobConfig.JOB_NAME)
@Component
class BackfillOrderConfirmedOutboxTasklet(
    private val backfill: OrderConfirmedOutboxBackfill,
    @Value("#{jobParameters['orderIds']}")
    private val orderIds: String?,
) : Tasklet {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        // 명령줄 job 파라미터는 쉼표를 타입 구분자로 읽으므로 세미콜론으로 나눔
        val targets = requireNotNull(orderIds) { "대상 주문을 orderIds=1;2 처럼 지정해야 합니다." }
            .split(";").map { it.trim().toLong() }.distinct()
        val results = targets.map(backfill::backfill)
        results.forEach { log.info("outbox 채우기 결과: {}", it) }

        val created = results.count { it is OrderConfirmedOutboxBackfill.Result.Created }
        contribution.incrementWriteCount(created.toLong())
        val failed = results.filterIsInstance<OrderConfirmedOutboxBackfill.Result.Failed>()
        check(failed.isEmpty()) { "outbox 를 채우지 못한 주문: ${failed.joinToString { "${it.orderId}(${it.reason})" }}" }
        return RepeatStatus.FINISHED
    }
}
