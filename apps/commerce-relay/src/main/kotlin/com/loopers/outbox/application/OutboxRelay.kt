package com.loopers.outbox.application

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import com.loopers.outbox.domain.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZonedDateTime

@Component
class OutboxRelay(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxPublisher: OutboxPublisher,
    private val properties: OutboxRelayProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    private val relayName: OutboxRelayName,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    // REPEATABLE READ 면 후보 범위의 gap 잠금이 확정 트랜잭션의 outbox insert 를 막음
    private val claimTransaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }
    private val retryPolicy = OutboxRetryPolicy(properties.maxAttempts, properties.backoffInitial, properties.backoffMax)

    /** 선점한 행 수 반환, 배치 크기와 같으면 밀린 행이 더 있을 수 있음 */
    fun runOnce(): Int {
        val events = claim()
        if (events.isEmpty()) return 0

        val results = outboxPublisher.publish(events)
        transaction.executeWithoutResult { record(results) }
        return events.size
    }

    private fun claim(): List<OutboxEvent> {
        val now = now()
        return requireNotNull(
            claimTransaction.execute {
                outboxEventRepository.claim(
                    relayName = relayName.value,
                    now = now,
                    claimedUntil = now.plus(properties.lease),
                    limit = properties.batchSize,
                    candidateLimit = properties.candidateSize,
                )
            },
        )
    }

    private fun record(results: List<OutboxPublishResult>) {
        val now = now()
        val published = results.filterIsInstance<OutboxPublishResult.Published>()
        val marked = outboxEventRepository.markPublished(published.map { it.event.id }, relayName.value, now)
        published.forEach { log.info("outbox 발행 완료: id={}, eventId={}", it.event.id, it.event.eventId) }
        if (marked < published.size) {
            log.warn(
                "outbox 완료 기록 누락, 다른 relay 가 다시 선점했거나 이미 처리함: relay={}, 보낸 행={}, 기록한 행={}",
                relayName.value,
                published.size,
                marked,
            )
        }

        results.filterIsInstance<OutboxPublishResult.Failed>().forEach { recordFailure(it, now) }
    }

    private fun recordFailure(result: OutboxPublishResult.Failed, now: ZonedDateTime) {
        val event = result.event
        val attemptCount = event.attemptCount + 1
        val error = withCauses(result.error)
        if (!result.retryable || retryPolicy.isExhausted(attemptCount)) {
            val marked = outboxEventRepository.markFailed(event.id, relayName.value, attemptCount, error, now)
            log.error("outbox 발행 중단: id={}, eventId={}, attemptCount={}", event.id, event.eventId, attemptCount, result.error)
            if (marked == 0) warnLostClaim(event)
            return
        }
        val nextAttemptAt = retryPolicy.nextAttemptAt(attemptCount, now)
        val marked = outboxEventRepository.scheduleRetry(event.id, relayName.value, attemptCount, nextAttemptAt, error, now)
        log.warn(
            "outbox 발행 재시도 예약: id={}, eventId={}, attemptCount={}, nextAttemptAt={}, error={}",
            event.id,
            event.eventId,
            attemptCount,
            nextAttemptAt,
            error,
        )
        if (marked == 0) warnLostClaim(event)
    }

    private fun warnLostClaim(event: OutboxEvent) {
        log.warn(
            "outbox 결과 기록 누락, 다른 relay 가 다시 선점했거나 이미 처리함: relay={}, id={}, eventId={}",
            relayName.value,
            event.id,
            event.eventId,
        )
    }

    private fun withCauses(error: Throwable): String =
        generateSequence(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}
