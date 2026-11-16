package com.loopers.config.redis

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(value = "datasource.redis")
data class RedisProperties(
    val database: Int,
    val master: RedisNodeInfo,
    val replicas: List<RedisNodeInfo>,
    val commandTimeout: Duration = Duration.ofSeconds(1),
)
