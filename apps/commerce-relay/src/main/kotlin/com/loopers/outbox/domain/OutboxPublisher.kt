package com.loopers.outbox.domain

/** 배치의 행마다 결과 하나, 입력 순서 유지 */
interface OutboxPublisher {
    fun publish(events: List<OutboxEvent>): List<OutboxPublishResult>
}

sealed interface OutboxPublishResult {
    val event: OutboxEvent

    data class Published(override val event: OutboxEvent) : OutboxPublishResult

    data class Failed(
        override val event: OutboxEvent,
        val error: Throwable,
        val retryable: Boolean,
    ) : OutboxPublishResult
}
