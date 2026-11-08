package com.loopers.outbox.application

import java.util.UUID

data class OutboxRelayName(val value: String) {
    companion object {
        fun generate(): OutboxRelayName = OutboxRelayName(
            "${System.getenv("HOSTNAME") ?: "local"}-${UUID.randomUUID().toString().take(8)}",
        )
    }
}
