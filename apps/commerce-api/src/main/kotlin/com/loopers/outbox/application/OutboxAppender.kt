package com.loopers.outbox.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 호출한 쪽 트랜잭션에 outbox 행 추가, 업무 변경과 함께 커밋·롤백
 * 같은 eventId 를 다시 넣으면 unique 위반 예외
 */
@Component
class OutboxAppender(
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun append(
        eventId: String,
        aggregateType: String,
        aggregateId: String,
        eventType: String,
        schemaVersion: Int,
        topic: String,
        payload: Any,
    ): OutboxEvent = outboxEventRepository.save(
        OutboxEvent(
            eventId = eventId,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            schemaVersion = schemaVersion,
            topic = topic,
            payload = objectMapper.writeValueAsString(payload),
            now = ZonedDateTime.now(clock),
        ),
    )
}
