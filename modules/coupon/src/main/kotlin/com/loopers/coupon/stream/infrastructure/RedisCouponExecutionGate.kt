package com.loopers.coupon.stream.infrastructure

import com.loopers.config.redis.RedisConfig
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponGateStatus
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RedisCouponExecutionGate(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
    @Value("\${coupon.stream.control-prefix:coupon:stream:control}") private val prefix: String,
    @Value("\${coupon.admission.capacity:3}") private val capacity: Int,
) : CouponExecutionGate {
    private enum class Execution { RUNNING, FINISHED, UNKNOWN }
    private val log = LoggerFactory.getLogger(javaClass)
    private val owner = UUID.randomUUID().toString()
    private val owned = linkedMapOf<String, Execution>()
    private var lastEpoch: String? = null
    private var failedEpoch: String? = null
    private var blocked = false
    private val keys get() = listOf("$prefix:state", "$prefix:active")

    init {
        require(capacity > 0 && prefix.isNotBlank())
    }

    @Synchronized
    override fun status(): CouponGateStatus {
        val values = try {
            redis.execute(statusScript, keys).orEmpty().map { it.toString() }
        } catch (error: RuntimeException) {
            fail(error)
            throw CouponStreamException(CouponStreamFailure.UNAVAILABLE, error)
        }
        if (values.size < 3) throw CouponStreamException(CouponStreamFailure.STATE_LOST)
        val epoch = values[1].ifBlank { null }
        if (blocked && failedEpoch != null && epoch != failedEpoch) blocked = false
        if (blocked && failedEpoch == null) {
            failedEpoch = epoch
            runCatching { redis.execute(closeScript, keys, "REDIS_FAILURE", UUID.randomUUID().toString()) }
        }
        lastEpoch = epoch
        return CouponGateStatus(
            accepting = values[0] == "OPEN" && !blocked,
            activeTokens = values.drop(3).toSet(),
            epoch = epoch,
            reason = if (blocked) "REDIS_FAILURE" else values[2].ifBlank { null },
        )
    }

    @Synchronized
    override fun close(): CouponGateStatus {
        try {
            redis.execute(closeScript, keys, "MANUAL_CLOSED", UUID.randomUUID().toString())
        } catch (error: RuntimeException) {
            fail(error)
            throw CouponStreamException(CouponStreamFailure.UNAVAILABLE, error)
        }
        return status()
    }

    @Synchronized
    override fun recover(confirmed: Boolean, expectedTokens: Set<String>): CouponGateStatus {
        if (!confirmed) throw CouponStreamException(CouponStreamFailure.INVALID_REQUEST)
        if (owned.values.any { it == Execution.RUNNING }) {
            throw CouponStreamException(CouponStreamFailure.RECOVERY_CONFLICT)
        }
        val result = try {
            redis.execute(
                recoverScript,
                keys,
                UUID.randomUUID().toString(),
                capacity.toString(),
                *expectedTokens.sorted().toTypedArray(),
            )
        } catch (error: RuntimeException) {
            fail(error)
            throw CouponStreamException(CouponStreamFailure.UNAVAILABLE, error)
        }
        if (result != 1L) throw CouponStreamException(CouponStreamFailure.RECOVERY_CONFLICT)
        blocked = false
        failedEpoch = null
        owned.clear()
        return status()
    }

    @Synchronized
    override fun acquire(): String? {
        val state = status()
        if (!state.accepting) throw CouponStreamException(CouponStreamFailure.CLOSED)
        val token = "$owner:${UUID.randomUUID()}"
        val running = owned.filterValues { it == Execution.RUNNING }.keys
        owned[token] = Execution.UNKNOWN
        val result = try {
            redis.execute(acquireScript, keys, capacity.toString(), token, *running.toTypedArray())
        } catch (error: RuntimeException) {
            fail(error)
            throw CouponStreamException(CouponStreamFailure.UNAVAILABLE, error)
        }
        return when (result) {
            1L -> {
                owned[token] = Execution.RUNNING
                token
            }
            0L -> {
                owned.remove(token)
                null
            }
            -1L -> {
                owned.remove(token)
                throw CouponStreamException(CouponStreamFailure.CLOSED)
            }
            else -> {
                owned.remove(token)
                throw CouponStreamException(CouponStreamFailure.STATE_LOST)
            }
        }
    }

    @Synchronized
    override fun release(token: String) {
        if (owned[token] != Execution.RUNNING) return
        owned[token] = Execution.FINISHED
        try {
            if (redis.opsForSet().remove(keys[1], token) == 1L) {
                owned.remove(token)
            } else {
                fail(IllegalStateException("실행 토큰 유실"))
            }
        } catch (error: RuntimeException) {
            fail(error)
        }
    }

    @Synchronized
    override fun retainUncertain(token: String) {
        if (owned[token] != Execution.RUNNING) return
        owned[token] = Execution.UNKNOWN
        fail(IllegalStateException("DB 종료 확인 필요"))
    }

    private fun fail(error: RuntimeException) {
        blocked = true
        failedEpoch = lastEpoch
        log.error("쿠폰 전체 수용 닫힘 owner={}", owner, error)
        runCatching { redis.execute(closeScript, keys, "STATE_UNCERTAIN", UUID.randomUUID().toString()) }
    }

    companion object {
        private fun <T> script(name: String, type: Class<T>) = DefaultRedisScript<T>().apply {
            setLocation(ClassPathResource("redis/coupon-stream-gate-$name.lua"))
            resultType = type
        }

        @Suppress("UNCHECKED_CAST")
        private val statusScript = script("status", List::class.java) as DefaultRedisScript<List<Any>>
        private val closeScript = script("close", Long::class.java)
        private val recoverScript = script("recover", Long::class.java)
        private val acquireScript = script("acquire", Long::class.java)
    }
}
