package com.loopers.config

import com.loopers.purchase.application.PurchaseSourceRecovery
import com.loopers.purchase.domain.InvalidPurchaseEventException
import com.loopers.purchase.domain.PurchaseContentConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.purchase.interfaces.consumer.OrderConfirmedValidator
import com.loopers.purchase.interfaces.consumer.OrderEventMessage
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Duration

@Configuration
class PurchaseKafkaConfig {
    @Bean
    fun purchaseDltProducerFactory(properties: KafkaProperties): DefaultKafkaProducerFactory<String, ByteArray> {
        val config = HashMap(properties.buildProducerProperties()).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java)
            put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000)
            put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000)
            put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000)
        }
        return DefaultKafkaProducerFactory(config)
    }

    @Bean
    fun purchaseDltTemplate(
        @Qualifier("purchaseDltProducerFactory") factory: DefaultKafkaProducerFactory<String, ByteArray>,
    ): KafkaTemplate<String, ByteArray> = KafkaTemplate(factory)

    @Bean
    fun purchaseDltPublisher(
        @Qualifier("purchaseDltTemplate") template: KafkaTemplate<String, ByteArray>,
        @Value("\${commerce.kafka.order-dlt-topic:\${commerce.kafka.order-topic:order-events}.DLT}") topic: String,
    ): DeadLetterPublishingRecoverer = object : DeadLetterPublishingRecoverer(
        template,
        { record, _ -> TopicPartition(topic, record.partition()) },
    ) {
        override fun createProducerRecord(
            record: ConsumerRecord<*, *>,
            destination: TopicPartition,
            headers: Headers,
            key: ByteArray?,
            value: ByteArray?,
        ): ProducerRecord<Any, Any> {
            val original = record.headers().lastHeader(OrderEventJsonDeserializer.ORIGINAL_VALUE_HEADER)?.value()
            headers.remove(OrderEventJsonDeserializer.ORIGINAL_VALUE_HEADER)
            return super.createProducerRecord(record, destination, headers, key, original ?: value)
        }
    }.apply {
        setFailIfSendResultIsError(true)
        setWaitForSendResultTimeout(Duration.ofSeconds(20))
    }

    @Bean
    fun purchaseActivityListenerFactory(
        properties: KafkaProperties,
        publisher: DeadLetterPublishingRecoverer,
        mapper: ObjectMapper,
        validator: OrderConfirmedValidator,
        recovery: PurchaseSourceRecovery,
        registry: ObjectProvider<KafkaListenerEndpointRegistry>,
    ): ConcurrentKafkaListenerContainerFactory<String, OrderEventMessage> {
        val log = LoggerFactory.getLogger("PurchaseActivityRecovery")
        val recoverer = ConsumerRecordRecoverer { record, error ->
            val causes = generateSequence<Throwable>(error) { it.cause }.toList()
            val invalid = causes.any {
                it is InvalidPurchaseEventException || it is PurchaseContentConflictException || it is DeserializationException
            }
            val transient = causes.any {
                it is TransientDataAccessException || it is DataAccessResourceFailureException ||
                    it is CannotCreateTransactionException
            }
            try {
                if (!invalid && !transient) throw error
                try {
                    publisher.accept(record, error)
                    log.error(
                        "구매 활동 DLT 보관: topic={}, partition={}, offset={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        error,
                    )
                } catch (sendError: Exception) {
                    if (invalid) throw sendError
                    val purchase = validator.validate(
                        record.key() as? String,
                        record.value() as? OrderEventMessage,
                    ) ?: throw sendError
                    if (!recovery.canRebuild(purchase)) throw sendError
                    log.error(
                        "구매 활동 미처리, 주문 원본으로 수동 복구 필요: eventId={}, orderId={}, topic={}, partition={}, offset={}",
                        purchase.eventId,
                        purchase.orderId,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        sendError,
                    )
                }
            } catch (failure: Exception) {
                log.error(
                    "구매 활동 소비 중단: topic={}, partition={}, offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    failure,
                )
                registry.getObject().getListenerContainer("purchase-activity")?.stop { }
                throw failure
            }
        }
        val backOff = ExponentialBackOffWithMaxRetries(3).apply {
            initialInterval = 1_000
            multiplier = 2.0
            maxInterval = 4_000
        }
        val handler = DefaultErrorHandler(recoverer, backOff).apply {
            setClassifications(
                mapOf(
                    TransientDataAccessException::class.java to true,
                    DataAccessResourceFailureException::class.java to true,
                    CannotCreateTransactionException::class.java to true,
                ),
                false,
            )
            setResetStateOnExceptionChange(false)
            setResetStateOnRecoveryFailure(false)
            setCommitRecovered(true)
        }
        return ConcurrentKafkaListenerContainerFactory<String, OrderEventMessage>().apply {
            consumerFactory = DefaultKafkaConsumerFactory(
                HashMap(properties.buildConsumerProperties()).apply {
                    put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer::class.java)
                },
                StringDeserializer(),
                ErrorHandlingDeserializer(OrderEventJsonDeserializer(mapper)),
            )
            setConcurrency(1)
            setAutoStartup(properties.listener.isAutoStartup)
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            setCommonErrorHandler(handler)
        }
    }
}
