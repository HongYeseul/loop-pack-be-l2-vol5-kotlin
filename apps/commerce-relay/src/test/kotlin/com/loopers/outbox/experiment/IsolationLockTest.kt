package com.loopers.outbox.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.outbox.domain.OutboxEvent
import com.loopers.outbox.domain.OutboxEventRepository
import com.loopers.outbox.infrastructure.OutboxEventJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/** 결과는 build/reports/week7/isolation-*.json */
@SpringBootTest
@DisplayName("relay 가 후보를 잠근 동안 격리 수준에 따른 확정 insert 차단")
class IsolationLockTest @Autowired constructor(
    private val entityManager: EntityManager,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val transactionManager: PlatformTransactionManager,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val databaseCleanUp: DatabaseCleanUp,
    environment: Environment,
) {
    companion object {
        private const val SELECT_AND_LOCK = """
            SELECT o.* FROM outbox o
            WHERE ((o.status = 'PENDING' AND o.next_attempt_at <= UTC_TIMESTAMP(6))
                OR (o.status = 'CLAIMED' AND o.claimed_until < UTC_TIMESTAMP(6)))
              AND NOT EXISTS (
                SELECT 1 FROM outbox p
                WHERE p.aggregate_type = o.aggregate_type AND p.aggregate_id = o.aggregate_id
                  AND p.id < o.id AND p.status IN ('PENDING', 'CLAIMED', 'FAILED')
              )
            ORDER BY o.id LIMIT 10
            FOR UPDATE SKIP LOCKED
        """
    }

    private val observer = LockObserver(environment)
    private val executor = Executors.newFixedThreadPool(2)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        databaseCleanUp.truncateAllTables()
    }

    @Test
    @DisplayName("한 문장으로 고르면서 잠그면, REPEATABLE READ 에서 relay 가 잠근 범위의 gap 에 막혀 확정 insert 가 잠금 대기 시간을 넘겨 실패한다")
    fun blocksInsertUnderRepeatableRead() {
        val observation = observe(TransactionDefinition.ISOLATION_REPEATABLE_READ, "repeatable-read", ::selectAndLock)

        assertThat(observation.insertSucceeded).isFalse()
        assertThat(observation.insertMillis).isGreaterThanOrEqualTo(2_500)
        assertThat(observation.waits).anySatisfy { assertThat(it["REQUESTING_LOCK_MODE"]).contains("INSERT_INTENTION") }
    }

    @Test
    @DisplayName("한 문장으로 고르면서 잠가도, READ COMMITTED 면 gap 을 잠그지 않아 확정 insert 가 바로 들어간다")
    fun allowsInsertUnderReadCommitted() {
        val observation = observe(TransactionDefinition.ISOLATION_READ_COMMITTED, "read-committed", ::selectAndLock)

        assertThat(observation.insertSucceeded).isTrue()
        assertThat(observation.insertMillis).isLessThan(1_000)
        assertThat(observation.waits).isEmpty()
    }

    @Test
    @DisplayName("고른 뒤 id 로 잠그는 지금 선점은 REPEATABLE READ 여도 고른 행만 잠가 확정 insert 가 바로 들어간다")
    fun allowsInsertWithPickThenLockEvenUnderRepeatableRead() {
        val observation = observe(TransactionDefinition.ISOLATION_REPEATABLE_READ, "pick-then-lock-repeatable-read", ::claim)

        assertThat(observation.insertSucceeded).isTrue()
        assertThat(observation.insertMillis).isLessThan(1_000)
        assertThat(observation.waits).isEmpty()
        assertThat(observation.relayLocks.filter { it["LOCK_TYPE"] == "RECORD" }).allSatisfy {
            assertThat(it["INDEX_NAME"]).isEqualTo("PRIMARY")
            assertThat(it["LOCK_MODE"]).isEqualTo("X,REC_NOT_GAP")
        }
    }

    private fun selectAndLock() {
        entityManager.createNativeQuery(SELECT_AND_LOCK).resultList
    }

    private fun claim() {
        val now = ZonedDateTime.now(clock)
        outboxEventRepository.claim("relay", now, now.plusSeconds(150), limit = 10, candidateLimit = 30)
    }

    private fun observe(isolation: Int, name: String, lockCandidates: () -> Unit): Observation {
        (1L..3L).forEach { save(it) }
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val relay = CompletableFuture.runAsync(
            {
                TransactionTemplate(transactionManager).apply { isolationLevel = isolation }.executeWithoutResult {
                    lockCandidates()
                    locked.countDown()
                    release.await(30, SECONDS)
                }
            },
            executor,
        )
        assertThat(locked.await(10, SECONDS)).isTrue()
        val relayLocks = observer.locks()

        val insert = CompletableFuture.supplyAsync(
            {
                val begin = System.nanoTime()
                val result = runCatching { save(100) }
                Triple(result.isSuccess, (System.nanoTime() - begin) / 1_000_000, result.exceptionOrNull()?.let(::rootMessage))
            },
            executor,
        )
        Thread.sleep(500)
        val waits = observer.waits()
        val (succeeded, millis, error) = insert.get(10, SECONDS)
        release.countDown()
        relay.get(10, SECONDS)

        return Observation(name, relayLocks, waits, succeeded, millis, error).also(::saveEvidence)
    }

    private fun save(orderId: Long) {
        outboxEventJpaRepository.save(
            OutboxEvent(
                eventId = "order-confirmed:v1:$orderId",
                aggregateType = "ORDER",
                aggregateId = orderId.toString(),
                eventType = "OrderConfirmed",
                schemaVersion = 1,
                topic = "order-events",
                payload = """{"orderId":$orderId}""",
                now = ZonedDateTime.now(clock),
            ),
        )
    }

    private fun rootMessage(error: Throwable): String? = generateSequence(error) { it.cause }.last().message

    private fun saveEvidence(observation: Observation) {
        val directory = Path.of("build", "reports", "week7")
        Files.createDirectories(directory)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("isolation-${observation.isolation}.json").toFile(), observation)
    }

    data class Observation(
        val isolation: String,
        val relayLocks: List<Map<String, String?>>,
        val waits: List<Map<String, String?>>,
        val insertSucceeded: Boolean,
        val insertMillis: Long,
        val insertError: String?,
    )
}
