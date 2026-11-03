package com.loopers.config

import com.loopers.testcontainers.KafkaTestFixture
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.ConsumerGroupDescription
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.RangeAssignor
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.ConsumerGroupState
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Testcontainers
@DisplayName("Kafka key 배정과 같은 group의 consumer 수 변경 실험")
class KafkaAssignmentExperimentTest {
    companion object {
        @Container
        @JvmField
        val kafka = KafkaTestFixture()
    }

    @Test
    @DisplayName("고정된 파티션 수에서 같은 key는 같은 파티션으로 발행한다")
    fun routesSameKeyToSamePartition() {
        withTopic("key-routing") { topic, _ ->
            KafkaProducer<String, String>(producerProperties()).use { producer ->
                val metadata = listOf("42", "42", "42", "7", "8", "9").mapIndexed { index, key ->
                    key to producer.send(ProducerRecord(topic, key, "key-probe-$index")).get(15, SECONDS)
                }

                assertThat(metadata.filter { it.first == "42" }.map { it.second.partition() }.toSet()).hasSize(1)

                writeReport(
                    "key-routing",
                    topic,
                    metadata.map { (key, record) ->
                        "key=$key -> partition=${record.partition()}, offset=${record.offset()}"
                    },
                )
            }
        }
    }

    @Test
    @DisplayName("같은 group의 consumer를 1→2→3개로 늘려도 각 파티션은 한 consumer만 담당한다")
    fun reassignsPartitionsWhenConsumersJoin() {
        withTopic("consumer-assignment") { topic, admin ->
            val group = "partition-experiment-${UUID.randomUUID()}"
            val partitions = (0..1).map { TopicPartition(topic, it) }.toSet()
            val observations = mutableListOf("group: $group", "consumer: enable.auto.commit=false, RangeAssignor")
            val workers = mutableListOf<PollingConsumer>()
            val received = LinkedBlockingQueue<Received>()
            KafkaProducer<String, String>(producerProperties()).use { producer ->
                try {
                    for (count in 1..3) {
                        workers.add(PollingConsumer("consumer-$count", topic, group, received))
                        val description = awaitStableAssignment(admin, group, workers, partitions)
                        val assignment = description.members().associate {
                            it.clientId() to it.assignment().topicPartitions().map(TopicPartition::partition).sorted()
                        }.toSortedMap()
                        assertThat(assignment.values.map(List<Int>::size).sorted()).isEqualTo(
                            when (count) {
                                1 -> listOf(2)
                                2 -> listOf(1, 1)
                                else -> listOf(0, 1, 1)
                            },
                        )
                        observations.add("consumer=$count, state=${description.state()}, assignment=$assignment")

                        val sent = (0..1).map { partition ->
                            producer.send(ProducerRecord(topic, partition, "stage-$count", "stage-$count-p$partition"))
                                .get(15, SECONDS)
                        }
                        val stageRecords = mutableListOf<Received>()
                        await().atMost(Duration.ofSeconds(20)).untilAsserted {
                            workers.forEach(PollingConsumer::assertHealthy)
                            val drained = mutableListOf<Received>()
                            received.drainTo(drained)
                            stageRecords.addAll(drained.filter { it.record.value().startsWith("stage-$count-") })
                            assertThat(stageRecords).hasSize(2)
                        }
                        assertThat(stageRecords.map { it.record.partition() to it.record.offset() }.toSet())
                            .isEqualTo(sent.map { it.partition() to it.offset() }.toSet())
                        stageRecords.sortedBy { it.record.partition() }.forEach {
                            assertThat(assignment.getValue(it.clientId)).contains(it.record.partition())
                            observations.add(
                                "  ${it.clientId} 수신: partition=${it.record.partition()}, " +
                                    "offset=${it.record.offset()}, value=${it.record.value()}",
                            )
                        }
                        assertCommitted(admin, group, sent)
                    }
                } finally {
                    val closed = workers.asReversed().map { runCatching { it.close() } }
                    closed.forEach { it.getOrThrow() }
                }
            }
            writeReport("consumer-assignment", topic, observations)
        }
    }

    private fun withTopic(name: String, action: (String, AdminClient) -> Unit) {
        Files.deleteIfExists(report(name))
        val topic = "$name-${UUID.randomUUID()}"
        AdminClient.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
            admin.createTopics(listOf(NewTopic(topic, 2, 1))).all().get(15, SECONDS)
            try {
                action(topic, admin)
            } finally {
                admin.deleteTopics(listOf(topic)).all().get(15, SECONDS)
            }
        }
    }

    private fun report(name: String): Path = Path.of("build/reports/kafka-experiments/$name.txt")

    private fun writeReport(name: String, topic: String, observations: List<String>) {
        val lines = listOf(
            "실행 시각: ${Instant.now()}",
            "실험 코드: KafkaAssignmentExperimentTest",
            "broker: apache/kafka-native:3.8.0, partitions=2, replication.factor=1",
            "bootstrap: ${kafka.bootstrapServers}",
            "topic: $topic",
            "producer: acks=all, enable.idempotence=true",
        ) + observations
        Files.createDirectories(report(name).parent)
        Files.writeString(report(name), lines.joinToString("\n", postfix = "\n"))
    }

    private fun awaitStableAssignment(
        admin: AdminClient,
        group: String,
        workers: List<PollingConsumer>,
        partitions: Set<TopicPartition>,
    ): ConsumerGroupDescription {
        var stable: ConsumerGroupDescription? = null
        await().atMost(Duration.ofSeconds(30)).ignoreException(ExecutionException::class.java).untilAsserted {
            workers.forEach(PollingConsumer::assertHealthy)
            val description = admin.describeConsumerGroups(listOf(group)).all().get(5, SECONDS).getValue(group)
            assertThat(description.state()).isEqualTo(ConsumerGroupState.STABLE)
            assertThat(description.members()).hasSize(workers.size)
            val assigned = description.members().flatMap { it.assignment().topicPartitions() }
            assertThat(assigned).containsExactlyInAnyOrderElementsOf(partitions)
            workers.forEach { worker ->
                val member = description.members().single { it.clientId() == worker.clientId }
                assertThat(worker.assignment.get()).isEqualTo(member.assignment().topicPartitions())
            }
            stable = description
        }
        return requireNotNull(stable)
    }

    private fun assertCommitted(admin: AdminClient, group: String, sent: List<RecordMetadata>) {
        val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, SECONDS)
        sent.forEach {
            assertThat(committed[TopicPartition(it.topic(), it.partition())]?.offset()).isEqualTo(it.offset() + 1)
        }
    }

    private fun producerProperties(): Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
    )

    private data class Received(val clientId: String, val record: ConsumerRecord<String, String>)

    private class PollingConsumer(
        val clientId: String,
        topic: String,
        group: String,
        received: LinkedBlockingQueue<Received>,
    ) : AutoCloseable {
        val assignment = AtomicReference<Set<TopicPartition>>(emptySet())
        private val running = AtomicBoolean(true)
        private val failure = AtomicReference<Throwable?>(null)
        private val consumer = KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to group,
                ConsumerConfig.CLIENT_ID_CONFIG to clientId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG to RangeAssignor::class.java.name,
            ),
        )
        private val thread = Thread({
            try {
                consumer.subscribe(listOf(topic))
                while (running.get()) {
                    val records = consumer.poll(Duration.ofMillis(100))
                    assignment.set(consumer.assignment().toSet())
                    if (!records.isEmpty) {
                        consumer.commitSync()
                        records.forEach { received.add(Received(clientId, it)) }
                    }
                }
            } catch (exception: WakeupException) {
                if (running.get()) failure.set(exception)
            } catch (exception: Throwable) {
                failure.set(exception)
            } finally {
                consumer.close(Duration.ofSeconds(5))
            }
        }, clientId).apply { start() }

        fun assertHealthy() {
            failure.get()?.let { throw AssertionError("$clientId poll 실패", it) }
        }

        override fun close() {
            running.set(false)
            consumer.wakeup()
            thread.join(10_000)
            assertThat(thread.isAlive).isFalse()
            assertHealthy()
        }
    }
}
