package com.loopers.outbox.application

import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import com.loopers.outbox.domain.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZonedDateTime

/** 선점 없이 읽으므로 relay 한 대 전제, 여러 대면 같은 행을 함께 발행 */
@Component
class OutboxRelay(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxPublisher: OutboxPublisher,
    private val properties: OutboxRelayProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)
    private val retryPolicy = OutboxRetryPolicy(properties.maxAttempts, properties.backoffInitial, properties.backoffMax)

    /** 읽어 온 행 수 반환, 배치 크기와 같으면 밀린 행이 더 있을 수 있음 */
    fun runOnce(): Int {
        val events = outboxEventRepository.findPublishable(now(), properties.batchSize)
        if (events.isEmpty()) return 0

        val results = outboxPublisher.publish(events)
        transaction.executeWithoutResult { record(results) }
        return events.size
    }

    private fun record(results: List<OutboxPublishResult>) {
        val now = now()
        val published = results.filterIsInstance<OutboxPublishResult.Published>()
        outboxEventRepository.markPublished(published.map { it.event.id }, now)
        published.forEach { log.info("outbox 발행 완료: id={}, eventId={}", it.event.id, it.event.eventId) }

        results.filterIsInstance<OutboxPublishResult.Failed>().forEach { recordFailure(it, now) }
    }

    private fun recordFailure(result: OutboxPublishResult.Failed, now: ZonedDateTime) {
        val event = result.event
        val attemptCount = event.attemptCount + 1
        val error = withCauses(result.error)
        if (!result.retryable || retryPolicy.isExhausted(attemptCount)) {
            outboxEventRepository.markFailed(event.id, attemptCount, error, now)
            log.error("outbox 발행 중단: id={}, eventId={}, attemptCount={}", event.id, event.eventId, attemptCount, result.error)
            return
        }
        val nextAttemptAt = retryPolicy.nextAttemptAt(attemptCount, now)
        outboxEventRepository.scheduleRetry(event.id, attemptCount, nextAttemptAt, error, now)
        log.warn(
            "outbox 발행 재시도 예약: id={}, eventId={}, attemptCount={}, nextAttemptAt={}, error={}",
            event.id,
            event.eventId,
            attemptCount,
            nextAttemptAt,
            error,
        )
    }

    private fun withCauses(error: Throwable): String =
        generateSequence(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}
