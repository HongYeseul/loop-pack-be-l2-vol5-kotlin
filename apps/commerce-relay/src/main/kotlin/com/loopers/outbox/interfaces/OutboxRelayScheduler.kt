package com.loopers.outbox.interfaces

import com.loopers.outbox.application.OutboxRelay
import com.loopers.outbox.application.OutboxRelayProperties
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = ["enabled"], havingValue = "true")
class OutboxRelayScheduler(
    private val outboxRelay: OutboxRelay,
    private val properties: OutboxRelayProperties,
) {
    @Volatile
    private var stopping = false

    /** 배치가 꽉 차면 주기를 기다리지 않고 이어서, 한 호출에 최대 maxRoundsPerTick 회차 */
    @Scheduled(fixedDelayString = "\${outbox.relay.poll-interval}")
    fun relay() {
        repeat(properties.maxRoundsPerTick) {
            if (stopping || outboxRelay.runOnce() < properties.batchSize) return
        }
    }

    @PreDestroy
    fun stop() {
        stopping = true
    }
}
