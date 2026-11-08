package com.loopers.outbox.domain

interface OutboxEventRepository {
    fun save(event: OutboxEvent): OutboxEvent
}
