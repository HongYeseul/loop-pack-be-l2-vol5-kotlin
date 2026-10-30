package com.loopers.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.config.kafka.KafkaConfig
import com.loopers.testcontainers.KafkaTestFixture
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.support.Acknowledgment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "commerce.kafka.purchase-activity-enabled=false",
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true",
    ],
)
@Import(KafkaSmokeTest.ListenerConfig::class)
@Testcontainers
@DisplayName("Kafka 연결·메시지 수신·offset 커밋 기본 검증")
class KafkaSmokeTest @Autowired constructor(
    private val template: KafkaTemplate<Any, Any>,
    private val consumerFactory: ConsumerFactory<Any, Any>,
    private val kafkaAdmin: KafkaAdmin,
    private val probeListener: ProbeListener,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val topic = "setup-probe-${UUID.randomUUID()}"
        private val group = "setup-probe-${UUID.randomUUID()}"

        @Container
        @JvmField
        val kafka = KafkaTestFixture()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("test.kafka.topic") { topic }
            registry.add("test.kafka.group") { group }
        }
    }

    @Test
    @DisplayName("공통 consumer는 자동 커밋 없이 bytes 역직렬화를 사용한다")
    fun usesManualByteArrayConsumerSettings() {
        val config = consumerFactory.configurationProperties

        assertThat(config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG]).isEqualTo(false)
        assertThat(config[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG]).isEqualTo(ByteArrayDeserializer::class.java)
    }

    @DisplayName("실제 Kafka에서 JSON을 bytes로 수신하고 ack한 위치를 commit한다.")
    @Test
    fun sendsReceivesAndCommits() {
        val metadata = template.send(topic, "setup", mapOf("eventType" to "SetupProbe"))
            .get(15, SECONDS).recordMetadata
        val record = requireNotNull(probeListener.messages.poll(15, SECONDS))

        assertThat(record.key()).isEqualTo("setup")
        assertThat(record.partition()).isEqualTo(metadata.partition())
        assertThat(record.offset()).isEqualTo(metadata.offset())
        assertThat(objectMapper.readTree(record.value() as ByteArray).path("eventType").asText()).isEqualTo("SetupProbe")

        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                val offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, SECONDS)
                val committed = offsets[TopicPartition(topic, metadata.partition())]
                assertThat(committed?.offset()).isEqualTo(metadata.offset() + 1)
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ListenerConfig {
        @Bean
        fun probeTopic(): NewTopic = NewTopic(topic, 2, 1)

        @Bean
        fun probeListener(): ProbeListener = ProbeListener()
    }

    class ProbeListener {
        val messages = LinkedBlockingQueue<ConsumerRecord<Any, Any>>()

        @KafkaListener(
            topics = ["\${test.kafka.topic}"],
            groupId = "\${test.kafka.group}",
            containerFactory = KafkaConfig.RECORD_LISTENER,
        )
        fun receive(record: ConsumerRecord<Any, Any>, acknowledgment: Acknowledgment) {
            acknowledgment.acknowledge()
            messages.add(record)
        }
    }
}
