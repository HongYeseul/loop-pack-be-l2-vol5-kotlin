package com.loopers.support

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** worker 의 시작 시점만 맞추고, 요청 안에는 어떤 대기도 넣지 않음 */
object ConcurrentRunner {
    private const val DEFAULT_TIMEOUT_SECONDS = 30L

    /** 작업마다 독립된 스레드에서 실행하고 성공·실패를 요청 순서대로 반환, 모든 worker 가 끝난 뒤에만 돌아옴 */
    fun <T> run(tasks: List<() -> T>, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(tasks.size)
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        try {
            val futures = tasks.map { task ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(start.await(timeoutSeconds, TimeUnit.SECONDS)) { "시작 신호 대기 시간 초과" }
                        runCatching(task)
                    },
                )
            }
            check(ready.await(timeoutSeconds, TimeUnit.SECONDS)) { "worker 준비 시간 초과" }
            start.countDown()
            return futures.map { it.get(timeoutSeconds, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
        }
    }
}
