package coupon.jpa.support

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun <T> reservedCouponRace(users: Int, work: (Long) -> T): List<Result<T>> {
    val executor = Executors.newFixedThreadPool(users)
    val ready = CountDownLatch(users)
    val start = CountDownLatch(1)
    try {
        val futures = (1..users).map { user ->
            executor.submit(
                Callable {
                ready.countDown()
                check(start.await(15, TimeUnit.SECONDS))
                runCatching { work(user.toLong()) }
            },
            )
        }
        check(ready.await(15, TimeUnit.SECONDS))
        start.countDown()
        return futures.map { it.get(15, TimeUnit.SECONDS) }
    } finally {
        start.countDown()
        executor.shutdownNow()
        check(executor.awaitTermination(15, TimeUnit.SECONDS))
    }
}
