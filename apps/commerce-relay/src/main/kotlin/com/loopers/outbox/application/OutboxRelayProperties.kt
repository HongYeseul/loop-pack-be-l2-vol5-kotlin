package com.loopers.outbox.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "outbox.relay")
data class OutboxRelayProperties(
    val enabled: Boolean,
    val pollInterval: Duration,
    val batchSize: Int,
    val maxRoundsPerTick: Int,
    val maxAttempts: Int,
    val backoffInitial: Duration,
    val backoffMax: Duration,
) {
    init {
        require(batchSize > 0)
        require(maxRoundsPerTick > 0)
    }
}
