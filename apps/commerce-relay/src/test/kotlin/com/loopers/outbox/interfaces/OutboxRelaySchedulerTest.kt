package com.loopers.outbox.interfaces

import com.loopers.outbox.application.OutboxRelay
import com.loopers.outbox.application.OutboxRelayProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxRelaySchedulerTest {
    private val outboxRelay = mockk<OutboxRelay>()
    private val scheduler = OutboxRelayScheduler(
        outboxRelay,
        OutboxRelayProperties(
            enabled = true,
            pollInterval = Duration.ofSeconds(1),
            batchSize = 10,
            maxRoundsPerTick = 3,
            maxAttempts = 10,
            backoffInitial = Duration.ofSeconds(1),
            backoffMax = Duration.ofSeconds(60),
            lease = Duration.ofSeconds(150),
            candidateSize = 30,
        ),
    )

    @DisplayName("배치가 덜 차면 한 회차만 돌고 끝낸다.")
    @Test
    fun stopsWhenBatchIsNotFull() {
        // arrange
        every { outboxRelay.runOnce() } returns 4

        // act
        scheduler.relay()

        // assert
        verify(exactly = 1) { outboxRelay.runOnce() }
    }

    @DisplayName("배치가 꽉 차면 이어서 돌고, 덜 찬 회차에서 끝낸다.")
    @Test
    fun continuesWhileBatchIsFull() {
        // arrange
        every { outboxRelay.runOnce() } returnsMany listOf(10, 2)

        // act
        scheduler.relay()

        // assert
        verify(exactly = 2) { outboxRelay.runOnce() }
    }

    @DisplayName("계속 꽉 차도 한 호출에 최대 회차까지만 돈다.")
    @Test
    fun stopsAtMaxRounds() {
        // arrange
        every { outboxRelay.runOnce() } returns 10

        // act
        scheduler.relay()

        // assert
        verify(exactly = 3) { outboxRelay.runOnce() }
    }

    @DisplayName("종료 신호를 받은 뒤에는 회차를 시작하지 않는다.")
    @Test
    fun doesNotRunAfterStop() {
        // arrange
        every { outboxRelay.runOnce() } returns 10
        scheduler.stop()

        // act
        scheduler.relay()

        // assert
        verify(exactly = 0) { outboxRelay.runOnce() }
    }
}
