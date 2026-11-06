package com.loopers.outbox.infrastructure

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

@Configuration
class OutboxKafkaConfig {
    /** 저장된 payload 를 다시 직렬화하지 않도록 바이트 그대로 전송 */
    @Bean
    fun outboxKafkaTemplate(kafkaProperties: KafkaProperties): KafkaTemplate<String, ByteArray> {
        val props = HashMap<String, Any>(kafkaProperties.buildProducerProperties()).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java)
        }
        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }
}
