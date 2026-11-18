package com.loopers.outbox.domain

import com.loopers.outbox.OutboxEvent

interface OutboxEventRepository {
    fun save(event: OutboxEvent): OutboxEvent
}
