package com.loopers.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StreamerConfigurationTest {
    @Test
    fun importedDefaultsDoNotOverrideStreamerSettings() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=test")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.environment.getProperty("spring.kafka.consumer.group-id")).isEqualTo("purchase-activity")
                assertThat(context.environment.getProperty("management.server.port")).isEqualTo("8083")
            }
    }
}
