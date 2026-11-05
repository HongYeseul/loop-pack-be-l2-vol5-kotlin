package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import org.springframework.stereotype.Component

@Component
class OutboxEventRepositoryImpl(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
) : OutboxEventRepository {
    override fun save(event: OutboxEvent): OutboxEvent = outboxEventJpaRepository.save(event)
}
