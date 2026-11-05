package com.loopers.batch.job.outbox

import com.loopers.batch.job.outbox.step.BackfillOrderConfirmedOutboxTasklet
import com.loopers.batch.listener.JobListener
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/** outbox 도입 전에 확정돼 이벤트가 빠진 주문을 지정해 outbox 에 한 번 채우는 일회성 job */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = BackfillOrderConfirmedOutboxJobConfig.JOB_NAME)
@Configuration
class BackfillOrderConfirmedOutboxJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jobListener: JobListener,
    private val stepMonitorListener: StepMonitorListener,
    private val tasklet: BackfillOrderConfirmedOutboxTasklet,
) {
    companion object {
        const val JOB_NAME = "backfillOrderConfirmedOutboxJob"
        private const val STEP_NAME = "backfillOrderConfirmedOutboxTask"
    }

    @Bean(JOB_NAME)
    fun backfillOrderConfirmedOutboxJob(): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .start(backfillOrderConfirmedOutboxStep())
            .listener(jobListener)
            .build()
    }

    @JobScope
    @Bean(STEP_NAME)
    fun backfillOrderConfirmedOutboxStep(): Step {
        return StepBuilder(STEP_NAME, jobRepository)
            .tasklet(tasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()
    }
}
