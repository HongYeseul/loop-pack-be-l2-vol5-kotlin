package com.loopers.batch.listener

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepExecution

class StepMonitorListenerTest {
    @Test
    fun `Step의 시작과 종료를 기록한다`() {
        withLogs { listener, logs ->
            val step = StepExecution("sampleStep", JobExecution(1L), 2L)
            step.status = BatchStatus.COMPLETED
            step.exitStatus = ExitStatus("CUSTOM_STATUS")

            listener.beforeStep(step)
            val status = listener.afterStep(step)

            assertThat(status).isNull()
            assertThat(step.exitStatus.exitCode).isEqualTo("CUSTOM_STATUS")
            assertThat(logs.list).hasSize(2)
            assertThat(logs.list[0].formattedMessage).contains("시작", "sampleStep")
            assertThat(logs.list[1].formattedMessage).contains("종료", "sampleStep", "COMPLETED")
        }
    }

    @Test
    fun `Step이 실패하면 원인을 오류 로그로 기록한다`() {
        withLogs { listener, logs ->
            val step = StepExecution("sampleStep", JobExecution(1L), 2L)
            step.status = BatchStatus.FAILED
            step.addFailureException(IllegalStateException("점수 저장 실패"))

            val status = listener.afterStep(step)

            assertThat(status).isNull()
            val errors = logs.list.filter { it.level == Level.ERROR }
            assertThat(errors).hasSize(1)
            assertThat(errors.single().formattedMessage).contains("sampleStep", "점수 저장 실패")
        }
    }

    private fun withLogs(block: (StepMonitorListener, ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(StepMonitorListener::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block(StepMonitorListener(), appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
