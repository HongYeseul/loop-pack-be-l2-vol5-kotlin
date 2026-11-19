package com.loopers.job.ranking.step.score

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import com.loopers.batch.job.ranking.step.score.WeeklyRankingProcessListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class WeeklyRankingProcessListenerTest {
    @Test
    fun `처리 오류에 상품 ID와 예외를 기록한다`() {
        val logger = LoggerFactory.getLogger(WeeklyRankingProcessListener::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            WeeklyRankingProcessListener().onProcessError(123L, IllegalStateException("조회 실패"))

            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.formattedMessage).contains("123")
            assertThat(event.throwableProxy.message).isEqualTo("조회 실패")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
