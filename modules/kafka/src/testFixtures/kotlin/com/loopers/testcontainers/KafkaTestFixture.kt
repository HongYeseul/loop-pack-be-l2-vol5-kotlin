package com.loopers.testcontainers

import org.testcontainers.kafka.KafkaContainer

class KafkaTestFixture : KafkaContainer("apache/kafka-native:3.8.0")
