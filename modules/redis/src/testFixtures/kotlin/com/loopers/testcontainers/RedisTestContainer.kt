package com.loopers.testcontainers

import org.testcontainers.containers.GenericContainer

class RedisTestContainer : GenericContainer<RedisTestContainer>("redis:7.0") {
    init {
        withExposedPorts(6379)
    }
}
