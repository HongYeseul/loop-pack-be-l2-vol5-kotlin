package com.loopers.utils

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration

/** 만든 시점 이후에 topic 에 쌓인 레코드만 읽음 */
class TopicReader(bootstrapServers: String, topic: String, partitionCount: Int = 2) : AutoCloseable {
    data class Record(
        val key: String,
        val headers: Map<String, String>,
        val partition: Int,
        val offset: Long,
        val value: String,
    ) {
        val eventId: String? get() = headers["eventId"]
    }

    private val partitions = (0 until partitionCount).map { TopicPartition(topic, it) }
    private val consumer = KafkaConsumer<String, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
    ).apply {
        assign(partitions)
        seekToEnd(partitions)
        partitions.forEach { position(it, Duration.ofSeconds(5)) }
    }

    /** count 개가 올 때까지 기다리고, 잠깐 더 읽어 그보다 많이 왔는지도 담아 돌려줌 */
    fun read(count: Int, timeout: Duration = Duration.ofSeconds(15)): List<Record> {
        val records = mutableListOf<Record>()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (records.size < count && System.nanoTime() < deadline) {
            records += poll(Duration.ofMillis(200))
        }
        records += poll(Duration.ofMillis(500))
        return records
    }

    /** 아무것도 오지 않아야 할 때, 잠깐 기다려 그사이 온 레코드만 돌려줌 */
    fun readRemaining(): List<Record> = poll(Duration.ofMillis(500))

    private fun poll(timeout: Duration): List<Record> = consumer.poll(timeout).map { record ->
        Record(
            key = record.key(),
            headers = record.headers().associate { it.key() to String(it.value(), Charsets.UTF_8) },
            partition = record.partition(),
            offset = record.offset(),
            value = String(record.value(), Charsets.UTF_8),
        )
    }

    override fun close() {
        consumer.close(Duration.ofSeconds(5))
    }
}
