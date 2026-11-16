package com.loopers.coupon.stream

import com.loopers.coupon.stream.domain.CouponStreamException
import com.loopers.coupon.stream.domain.CouponStreamFailure
import com.loopers.coupon.stream.infrastructure.RedisCouponExecutionGate
import com.loopers.coupon.stream.infrastructure.RedisCouponStream
import com.loopers.testcontainers.RedisTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class RedisCouponStreamIntegrationTest {
    @Test
    fun `두 클라이언트에서 1000명이 경쟁해도 예약과 작업은 100건이다`() {
        StreamFixture(redis).use { first ->
            StreamFixture(redis, first.prefix, initialize = false).use { second ->
                first.gate.recover(true)
                val stores = listOf(first.stream, second.stream)

                val outcomes = compete(1000) { index -> stores[index % 2].reserve(7, index + 1L) }

                assertThat(outcomes.count { it.isSuccess }).isEqualTo(100)
                outcomes.filter { it.isFailure }.forEach {
                    assertThat((it.exceptionOrNull() as CouponStreamException).failure).isEqualTo(CouponStreamFailure.FULL)
                }
                assertThat(first.stream.status(7, "issuers").reserved).isEqualTo(100)
                assertThat(first.stream.status(7, "issuers").entries).isEqualTo(100)
            }
        }
    }

    @Test
    fun `같은 회원의 동시 재요청은 같은 접수 ID와 작업 한 건을 보존한다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)

            val outcomes = compete(100) { fixture.stream.reserve(7, 42) }.map { it.getOrThrow() }

            assertThat(outcomes.count { it.created }).isEqualTo(1)
            assertThat(outcomes.map { it.streamId }.distinct()).hasSize(1)
            val state = fixture.stream.status(7, "issuers")
            assertThat(state.reserved).isEqualTo(1)
            assertThat(state.entries).isEqualTo(1)
        }
    }

    @Test
    fun `수용이 닫히면 신규 예약과 Stream 작업은 생기지 않는다`() {
        StreamFixture(redis).use { fixture ->
            val error = assertThrows<CouponStreamException> { fixture.stream.reserve(7, 42) }

            assertThat(error.failure).isEqualTo(CouponStreamFailure.CLOSED)
            assertThat(fixture.stream.status(7, "issuers").reserved).isZero()
            assertThat(fixture.stream.status(7, "issuers").entries).isZero()
        }
    }

    @Test
    fun `나중에 만들어진 Consumer Group도 먼저 접수된 작업을 읽는다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)
            val accepted = fixture.stream.reserve(7, 42)
            fixture.stream.ensureGroup(7, "late")

            val received = fixture.stream.read(7, "late", "consumer-a", 0)

            assertThat(received!!.id).isEqualTo(accepted.streamId)
            assertThat(received.userId).isEqualTo(42)
            assertThat(fixture.stream.status(7, "late").pending).isEqualTo(1)
        }
    }

    @Test
    fun `ACK 전 작업은 새 consumer가 회수하고 ACK 뒤에도 회원과 원본 항목은 남는다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)
            val accepted = fixture.stream.reserve(7, 42)
            fixture.stream.read(7, "issuers", "consumer-a", 0)

            val repeated = fixture.stream.claimPending(7, "issuers", "consumer-b", 0)
            fixture.stream.acknowledge(7, "issuers", repeated!!.id)

            assertThat(repeated.id).isEqualTo(accepted.streamId)
            val state = fixture.stream.status(7, "issuers")
            assertThat(state.pending).isZero()
            assertThat(state.reserved).isEqualTo(1)
            assertThat(state.entries).isEqualTo(1)
            assertThat(fixture.stream.read(7, "issuers", "consumer-b", 0)).isNull()
            assertThat(fixture.stream.reservation(7, 42)).isEqualTo(accepted.streamId)
        }
    }

    @Test
    fun `자기 consumer의 미확인 작업을 새 작업보다 먼저 다시 읽을 수 있다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)
            val first = fixture.stream.reserve(7, 41)
            fixture.stream.read(7, "issuers", "consumer-a", 0)
            fixture.stream.reserve(7, 42)

            val repeated = fixture.stream.readPending(7, "issuers", "consumer-a")

            assertThat(repeated!!.id).isEqualTo(first.streamId)
            assertThat(repeated.userId).isEqualTo(41)
        }
    }

    @Test
    fun `다시 초기화해도 미발급 예약과 최초 접수 ID를 덮어쓰지 않는다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)
            val accepted = fixture.stream.reserve(7, 42)
            fixture.gate.close()

            fixture.stream.initializeCampaign(7, 100, emptyList())

            assertThat(fixture.stream.reservation(7, 42)).isEqualTo(accepted.streamId)
            assertThat(fixture.stream.status(7, "issuers").entries).isEqualTo(1)
        }
    }

    @Test
    fun `수량 키가 유실되면 새로운 행사로 취급하지 않고 전체 수용을 닫는다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)
            fixture.redis.delete("${fixture.prefix}:{7}:quota")

            val error = assertThrows<CouponStreamException> { fixture.stream.reserve(7, 42) }

            assertThat(error.failure).isEqualTo(CouponStreamFailure.STATE_LOST)
            assertThat(fixture.gate.status().accepting).isFalse()
            assertThat(fixture.redis.opsForSet().size("${fixture.prefix}:{7}:members")!!).isZero()
        }
    }

    @Test
    fun `잘못된 Stream 타입은 회원을 추가하기 전에 발견한다`() {
        StreamFixture(redis).use { fixture ->
            fixture.gate.recover(true)
            fixture.redis.delete("${fixture.prefix}:{7}:requests")
            fixture.redis.opsForValue().set("${fixture.prefix}:{7}:requests", "wrong-type")

            val error = assertThrows<CouponStreamException> { fixture.stream.reserve(7, 42) }

            assertThat(error.failure).isEqualTo(CouponStreamFailure.STATE_LOST)
            assertThat(fixture.redis.opsForSet().size("${fixture.prefix}:{7}:members")!!).isZero()
            assertThat(fixture.gate.status().accepting).isFalse()
        }
    }

    @Test
    fun `Consumer Group 생성은 유실된 Stream을 빈 큐로 다시 만들지 않는다`() {
        StreamFixture(redis).use { fixture ->
            fixture.redis.delete("${fixture.prefix}:{7}:requests")

            val error = assertThrows<CouponStreamException> { fixture.stream.ensureGroup(7, "new-group") }

            assertThat(error.failure).isEqualTo(CouponStreamFailure.STATE_LOST)
            assertThat(fixture.redis.hasKey("${fixture.prefix}:{7}:requests")).isFalse()
        }
    }

    @Test
    fun `두 인스턴스가 같은 실행 키를 사용하면 전체 세 자리만 획득한다`() {
        StreamFixture(redis).use { first ->
            StreamFixture(redis, first.prefix, initialize = false).use { second ->
                first.gate.recover(true)
                val firstToken = first.gate.acquire()!!
                val secondToken = second.gate.acquire()!!
                first.gate.acquire()

                assertThat(second.gate.acquire()).isNull()
                first.gate.release("unknown")
                assertThat(first.gate.status().activeTokens).hasSize(3)
                first.gate.release(firstToken)
                assertThat(second.gate.acquire()).isNotNull()
                assertThat(second.gate.status().activeTokens).contains(secondToken).hasSize(3)
            }
        }
    }

    @Test
    fun `운영자가 정확히 확인한 잔여 토큰만 재개하면서 다른 인스턴스도 새 epoch를 본다`() {
        StreamFixture(redis).use { first ->
            StreamFixture(redis, first.prefix, initialize = false).use { operator ->
                first.gate.recover(true)
                val token = first.gate.acquire()!!
                first.gate.retainUncertain(token)

                assertThat(assertThrows<CouponStreamException> { operator.gate.recover(true) }.failure)
                    .isEqualTo(CouponStreamFailure.RECOVERY_CONFLICT)
                operator.gate.recover(true, setOf(token))

                assertThat(first.gate.status().accepting).isTrue()
                assertThat(first.gate.acquire()).isNotNull()
            }
        }
    }

    private fun <T> compete(count: Int, action: (Int) -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(minOf(count, 32))
        val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                executor.submit(
                    Callable {
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching { action(index) }
                },
                )
            }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    companion object {
        @Container @JvmStatic
        val redis = RedisTestContainer()
    }
}

private class StreamFixture(
    server: RedisTestContainer,
    val prefix: String = "coupon-stream-test:${UUID.randomUUID()}",
    initialize: Boolean = true,
) : AutoCloseable {
    private val connection = LettuceConnectionFactory(
        RedisStandaloneConfiguration(server.host, server.firstMappedPort),
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build(),
    ).apply {
        afterPropertiesSet()
        start()
    }
    val redis = StringRedisTemplate(connection)
    val gate = RedisCouponExecutionGate(redis, "$prefix:control", 3)
    val stream = RedisCouponStream(redis, gate, prefix, "$prefix:control")

    init {
        if (initialize) {
            stream.initializeCampaign(7, 100)
            stream.ensureGroup(7, "issuers")
        }
    }

    override fun close() {
        try {
            redis.delete(
                listOf("$prefix:control:state", "$prefix:control:active") +
                    listOf("quota", "members", "receipts", "requests").map { "$prefix:{7}:$it" },
            )
        } finally {
            connection.destroy()
        }
    }
}
