package com.loopers.config

import com.loopers.outbox.application.OutboxRelayName
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OutboxRelayNameConfig {
    @Bean
    fun outboxRelayName(): OutboxRelayName = OutboxRelayName.generate()
}
