package com.loopers.batch.job.order

import com.loopers.batch.job.order.step.ExpireOrdersTasklet
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

/**
 * 만료된 DRAFT 를 EXPIRED 로 정리한다 (DS-4).
 *
 * 확정 경로는 만료를 **거절만** 하므로(설계 5절 ⑤) 상태를 바꾸는 것은 이 job 뿐이다.
 */
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = ExpireOrdersJobConfig.JOB_NAME)
@Configuration
class ExpireOrdersJobConfig(
    private val jobRepository: JobRepository,
    // [DemoJobConfig] 와 달리 실제 DB 를 바꾸므로 JPA 트랜잭션 매니저가 필요하다.
    private val transactionManager: PlatformTransactionManager,
    private val jobListener: JobListener,
    private val stepMonitorListener: StepMonitorListener,
    private val expireOrdersTasklet: ExpireOrdersTasklet,
) {
    companion object {
        const val JOB_NAME = "expireOrdersJob"
        private const val STEP_EXPIRE_ORDERS_NAME = "expireOrdersTask"
    }

    @Bean(JOB_NAME)
    fun expireOrdersJob(): Job {
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .start(expireOrdersStep())
            .listener(jobListener)
            .build()
    }

    @JobScope
    @Bean(STEP_EXPIRE_ORDERS_NAME)
    fun expireOrdersStep(): Step {
        return StepBuilder(STEP_EXPIRE_ORDERS_NAME, jobRepository)
            .tasklet(expireOrdersTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()
    }
}
