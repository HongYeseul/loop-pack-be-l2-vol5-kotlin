package com.loopers.batch.listener

import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.stereotype.Component

@Component
class StepMonitorListener : StepExecutionListener {
    private val log = LoggerFactory.getLogger(StepMonitorListener::class.java)

    override fun beforeStep(stepExecution: StepExecution) {
        log.info("Step 시작: 이름={}, 실행 ID={}", stepExecution.stepName, stepExecution.id)
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        log.info(
            "Step 종료: 이름={}, 실행 ID={}, 상태={}, 읽기={}, 쓰기={}",
            stepExecution.stepName,
            stepExecution.id,
            stepExecution.status,
            stepExecution.readCount,
            stepExecution.writeCount,
        )
        if (stepExecution.status == BatchStatus.FAILED) {
            val cause = stepExecution.failureExceptions.firstOrNull()?.message?.lineSequence()?.firstOrNull() ?: "원인 미확인"
            log.error("Step 실패: 이름={}, 실행 ID={}, 원인={}", stepExecution.stepName, stepExecution.id, cause)
        }
        return null
    }
}
