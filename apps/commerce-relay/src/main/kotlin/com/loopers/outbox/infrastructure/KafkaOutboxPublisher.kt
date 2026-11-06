package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxPublishResult
import com.loopers.outbox.domain.OutboxPublisher
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

@Component
class KafkaOutboxPublisher(
    @Qualifier("outboxKafkaTemplate")
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
) : OutboxPublisher {
    override fun publish(events: List<OutboxEvent>): List<OutboxPublishResult> {
        val sends = sendAll(events)
        kafkaTemplate.flush()
        return events.zip(sends) { event, send -> await(event, send) }
    }

    /** 동기 예외(메타데이터 대기 초과 등)가 나면 남은 행도 같은 이유로 실패 처리, 행마다 max.block.ms 를 다시 기다리지 않음 */
    private fun sendAll(events: List<OutboxEvent>): List<CompletableFuture<*>> {
        val sends = mutableListOf<CompletableFuture<*>>()
        for (event in events) {
            try {
                sends += kafkaTemplate.send(record(event))
            } catch (exception: Exception) {
                val failed = CompletableFuture.failedFuture<Any>(exception)
                repeat(events.size - sends.size) { sends += failed }
                break
            }
        }
        return sends
    }

    private fun await(event: OutboxEvent, send: CompletableFuture<*>): OutboxPublishResult =
        try {
            send.get()
            OutboxPublishResult.Published(event)
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            OutboxPublishResult.Failed(event, cause, retryable = !isPermanent(cause))
        }

    private fun record(event: OutboxEvent): ProducerRecord<String, ByteArray> =
        ProducerRecord<String, ByteArray>(event.topic, event.aggregateId, event.payload.toByteArray(Charsets.UTF_8)).apply {
            headers().add(RecordHeader(HEADER_EVENT_ID, event.eventId.toByteArray(Charsets.UTF_8)))
            headers().add(RecordHeader(HEADER_EVENT_TYPE, event.eventType.toByteArray(Charsets.UTF_8)))
            headers().add(RecordHeader(HEADER_SCHEMA_VERSION, event.schemaVersion.toString().toByteArray(Charsets.UTF_8)))
        }

    private fun isPermanent(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause -> PERMANENT_ERRORS.any { it.isInstance(cause) } }

    companion object {
        const val HEADER_EVENT_ID = "eventId"
        const val HEADER_EVENT_TYPE = "eventType"
        const val HEADER_SCHEMA_VERSION = "schemaVersion"

        // 같은 레코드를 다시 보내도 결과가 같은 오류
        private val PERMANENT_ERRORS = listOf(
            SerializationException::class.java,
            RecordTooLargeException::class.java,
            InvalidTopicException::class.java,
        )
    }
}
