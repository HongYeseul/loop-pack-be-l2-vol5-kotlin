package com.loopers.config.kafka

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.loopers.testcontainers.KafkaTestFixture
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.support.serializer.JsonSerializer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

@Testcontainers
class OutboxPayloadSerializationTest {
    data class OrderConfirmed(val orderId: Long, val amount: Long)

    private val mapper = jacksonObjectMapper()
    private val event = OrderConfirmed(orderId = 1, amount = 1000)

    // outbox에 저장된 payload 컬럼 값
    private val storedPayload: String = mapper.writeValueAsString(event)

    @DisplayName("API가 객체를 JsonSerializer로 보내면 consumer는 JSON 객체를 받는다")
    @Test
    fun directPublishWithJsonSerializer() {
        val received = sendAndReceive(JsonSerializer<Any>(mapper)) { it }

        assertThat(String(received)).isEqualTo("""{"orderId":1,"amount":1000}""")
        assertThat(mapper.readValue<OrderConfirmed>(received)).isEqualTo(event)
    }

    @DisplayName("relay가 저장된 문자열을 JsonSerializer로 보내면 한 번 더 감싸져 consumer가 읽지 못한다")
    @Test
    fun relayWithJsonSerializer() {
        val received = sendAndReceive(JsonSerializer<Any>(mapper)) { storedPayload }

        assertThat(String(received)).isEqualTo(""""{\"orderId\":1,\"amount\":1000}"""")
        assertThrows<MismatchedInputException> { mapper.readValue<OrderConfirmed>(received) }
    }

    @DisplayName("relay가 저장된 문자열을 바이트 그대로 보내면 API가 직접 보낸 것과 같은 바이트가 간다")
    @Test
    fun relayWithByteArraySerializer() {
        val direct = sendAndReceive(JsonSerializer<Any>(mapper)) { it }
        val relayed = sendAndReceive(ByteArraySerializer()) { storedPayload.toByteArray() }

        assertThat(relayed).isEqualTo(direct)
        assertThat(mapper.readValue<OrderConfirmed>(relayed)).isEqualTo(event)
    }

    @DisplayName("StringSerializer도 문자열을 해석하지 않으므로 같은 바이트가 간다")
    @Test
    fun relayWithStringSerializer() {
        val direct = sendAndReceive(JsonSerializer<Any>(mapper)) { it }
        val relayed = sendAndReceive(StringSerializer()) { storedPayload }

        assertThat(relayed).isEqualTo(direct)
    }

    private fun <V> sendAndReceive(
        valueSerializer: Serializer<V>,
        value: (OrderConfirmed) -> Any,
    ): ByteArray {
        val topic = "outbox-payload-${UUID.randomUUID()}"
        createTopic(topic)

        KafkaProducer(mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers), StringSerializer(), valueSerializer).use { producer ->
            @Suppress("UNCHECKED_CAST")
            producer.send(ProducerRecord(topic, event.orderId.toString(), value(event) as V)).get()
        }

        // 역직렬화 없이 Kafka에 실제로 저장된 바이트를 꺼냄
        val consumerProps = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to topic,
        )
        KafkaConsumer(consumerProps, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
            val partition = TopicPartition(topic, 0)
            consumer.assign(listOf(partition))
            consumer.seekToBeginning(listOf(partition))
            return consumer.poll(Duration.ofSeconds(10)).single().value()
        }
    }

    private fun createTopic(topic: String) {
        AdminClient.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use {
            it.createTopics(listOf(NewTopic(topic, 1, 1))).all().get()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val kafka = KafkaTestFixture()
    }
}
