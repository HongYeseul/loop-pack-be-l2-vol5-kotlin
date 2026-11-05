package com.loopers.outbox.application

import com.loopers.outbox.domain.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZonedDateTime

/** 원인을 고친 FAILED 행을 다음 회차에 다시 보내게 함, 같은 주문의 뒤 행도 그 뒤로 이어서 나감 */
@Component
class OutboxResume(
    private val outboxEventRepository: OutboxEventRepository,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    fun resume(eventIds: List<String>): Map<String, Boolean> = eventIds.distinct().associateWith { eventId ->
        val resumed =
            requireNotNull(transaction.execute { outboxEventRepository.resumeFailed(eventId, ZonedDateTime.now(clock)) })
        if (resumed) {
            log.info("outbox 재개: eventId={}", eventId)
        } else {
            log.warn("outbox 재개 안 함, FAILED 행이 아님: eventId={}", eventId)
        }
        resumed
    }
}
