package com.loopers.coupon.stream.infrastructure

import com.loopers.config.redis.RedisConfig
import com.loopers.coupon.stream.domain.CouponExecutionGate
import com.loopers.coupon.stream.domain.CouponReservation
import com.loopers.coupon.stream.domain.CouponStream
import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.coupon.stream.domain.CouponStreamMessage
import com.loopers.coupon.stream.domain.CouponStreamStatus
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.output.IntegerOutput
import io.lettuce.core.output.NestedMultiOutput
import io.lettuce.core.output.StatusOutput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.lettuce.LettuceConnection
import org.springframework.data.redis.connection.DefaultStringRedisConnection
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class RedisCouponStream(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redis: RedisTemplate<String, String>,
    private val gate: CouponExecutionGate,
    @Value("\${coupon.stream.prefix:coupon:reservations}") private val prefix: String,
    @Value("\${coupon.stream.control-prefix:coupon:stream:control}") private val controlPrefix: String,
) : CouponStream {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun initializeCampaign(campaignId: Long, quota: Int, issuedUsers: List<Long>) {
        if (campaignId <= 0 || quota < 0 || issuedUsers.distinct().size > quota || issuedUsers.any { it <= 0 }) {
            throw CouponStreamException(CouponStreamFailure.INVALID_REQUEST)
        }
        if (gate.status().accepting) throw CouponStreamException(CouponStreamFailure.RECOVERY_CONFLICT)
        val result =
            execute(initializeScript, campaignKeys(campaignId), quota.toString(), *issuedUsers.map(Long::toString).toTypedArray())
        if (result != "OK") throw CouponStreamException(CouponStreamFailure.RECOVERY_CONFLICT)
    }

    override fun reserve(campaignId: Long, userId: Long): CouponReservation {
        if (campaignId <= 0 || userId <= 0) throw CouponStreamException(CouponStreamFailure.INVALID_REQUEST)
        val state = gate.status()
        if (!state.accepting) throw CouponStreamException(CouponStreamFailure.CLOSED)
        val result = execute(
            reserveScript,
            listOf("$controlPrefix:state") + campaignKeys(campaignId),
            campaignId.toString(),
            userId.toString(),
            state.epoch.orEmpty(),
        )
        return when {
            result?.startsWith("NEW|") == true -> CouponReservation(result.substringAfter('|'), true)
            result?.startsWith("EXISTING|") == true -> CouponReservation(result.substringAfter('|'), false)
            result == "FULL" -> throw CouponStreamException(CouponStreamFailure.FULL)
            result == "CLOSED" -> throw CouponStreamException(CouponStreamFailure.CLOSED)
            else -> stateLost()
        }
    }

    override fun reservation(campaignId: Long, userId: Long): String? {
        val result = execute(reservationScript, campaignKeys(campaignId), campaignId.toString(), userId.toString())
        return when {
            result == "NONE" -> null
            result?.startsWith("EXISTING|") == true -> result.substringAfter('|')
            else -> stateLost()
        }
    }

    override fun ensureGroup(campaignId: Long, group: String) {
        require(group.isNotBlank())
        if (execute(statusScript, campaignKeys(campaignId))?.split('|')?.size != 3) stateLost()
        command("XGROUP", listOf("CREATE", streamKey(campaignId), group, "0"), ignoreBusyGroup = true)
    }

    override fun read(campaignId: Long, group: String, consumer: String, blockMs: Long): CouponStreamMessage? {
        require(blockMs in 0..1000 && group.isNotBlank() && consumer.isNotBlank())
        val args = mutableListOf("GROUP", group, consumer, "COUNT", "1")
        if (blockMs > 0) args += listOf("BLOCK", blockMs.toString())
        args += listOf("STREAMS", streamKey(campaignId), ">")
        return readResponse(command("XREADGROUP", args), campaignId)
    }

    override fun readPending(campaignId: Long, group: String, consumer: String): CouponStreamMessage? = readResponse(
        command("XREADGROUP", listOf("GROUP", group, consumer, "COUNT", "1", "STREAMS", streamKey(campaignId), "0")),
        campaignId,
    )

    override fun claimPending(campaignId: Long, group: String, consumer: String, minIdleMs: Long): CouponStreamMessage? {
        require(minIdleMs >= 0)
        val result = command(
            "XAUTOCLAIM", listOf(streamKey(campaignId), group, consumer, minIdleMs.toString(), "0-0", "COUNT", "1"),
        ) as? List<*> ?: return null
        val entry = (result.getOrNull(1) as? List<*>)?.firstOrNull() ?: return null
        return message(entry, campaignId)
    }

    override fun acknowledge(campaignId: Long, group: String, messageId: String) {
        val result = command("XACK", listOf(streamKey(campaignId), group, messageId)) as? Number
        if (result == null || result.toLong() !in 0L..1L) stateLost()
    }

    override fun status(campaignId: Long, group: String): CouponStreamStatus {
        val values = execute(statusScript, campaignKeys(campaignId))?.split('|')
        if (values?.size != 3) stateLost()
        val pending = try {
            (command("XPENDING", listOf(streamKey(campaignId), group)) as? List<*>)?.firstOrNull() as? Number
        } catch (error: CouponStreamException) {
            throw error
        }
        return CouponStreamStatus(values[0].toInt(), values[1].toLong(), values[2].toLong(), pending?.toLong() ?: 0L)
    }

    private fun readResponse(response: Any?, campaignId: Long): CouponStreamMessage? {
        val values = response as? List<*> ?: return null
        val stream = if (values.firstOrNull() is ByteArray) values else values.firstOrNull() as? List<*> ?: return null
        val entry = (stream.getOrNull(1) as? List<*>)?.firstOrNull() ?: return null
        return message(entry, campaignId)
    }

    private fun message(raw: Any?, campaignId: Long): CouponStreamMessage {
        val entry = raw as? List<*> ?: stateLost()
        val fields = entry.getOrNull(1) as? List<*> ?: stateLost()
        if (fields.size % 2 != 0) stateLost()
        val record = fields.chunked(2).associate { text(it[0]) to text(it[1]) }
        val recordedCampaign = record["campaignId"]?.toLongOrNull() ?: stateLost()
        val userId = record["userId"]?.toLongOrNull() ?: stateLost()
        if (recordedCampaign != campaignId || userId <= 0) stateLost()
        return CouponStreamMessage(text(entry[0]), recordedCampaign, userId)
    }

    private fun command(name: String, args: List<String>, ignoreBusyGroup: Boolean = false): Any? = try {
        redis.execute(
            RedisCallback { connection ->
            val output = when (name) {
                "XGROUP" -> StatusOutput(ByteArrayCodec.INSTANCE)
                "XACK" -> IntegerOutput(ByteArrayCodec.INSTANCE)
                else -> NestedMultiOutput(ByteArrayCodec.INSTANCE)
            }
            val delegate = if (connection is DefaultStringRedisConnection) connection.delegate else connection
            (delegate as LettuceConnection).execute(
                name,
                output,
                *args.map { value -> value.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            )
        },
            true,
        )
    } catch (error: RuntimeException) {
        if (ignoreBusyGroup && generateSequence<Throwable>(error) { it.cause }.any { "BUSYGROUP" in it.message.orEmpty() }) {
            null
        } else {
            unavailable(error)
        }
    }

    private fun execute(script: DefaultRedisScript<String>, keys: List<String>, vararg args: String): String? = try {
        redis.execute(script, keys, *args)
    } catch (error: RuntimeException) {
        unavailable(error)
    }

    private fun unavailable(error: RuntimeException): Nothing {
        log.error("쿠폰 예약·작업 저장소 확인 실패", error)
        runCatching { gate.close() }
        throw CouponStreamException(CouponStreamFailure.UNAVAILABLE, error)
    }

    private fun stateLost(): Nothing {
        runCatching { gate.close() }
        throw CouponStreamException(CouponStreamFailure.STATE_LOST)
    }

    private fun campaignKeys(campaignId: Long): List<String> {
        val base = "$prefix:{$campaignId}"
        return listOf("$base:quota", "$base:members", "$base:receipts", "$base:requests")
    }

    private fun streamKey(campaignId: Long) = campaignKeys(campaignId)[3]
    private fun text(value: Any?): String = when (value) {
        is ByteArray -> value.toString(Charsets.UTF_8)
        is String -> value
        else -> stateLost()
    }

    companion object {
        private fun script(name: String) = DefaultRedisScript<String>().apply {
            setLocation(ClassPathResource("redis/coupon-stream-$name.lua"))
            resultType = String::class.java
        }
        private val initializeScript = script("initialize")
        private val reserveScript = script("reserve")
        private val reservationScript = script("reservation")
        private val statusScript = script("status")
    }
}
