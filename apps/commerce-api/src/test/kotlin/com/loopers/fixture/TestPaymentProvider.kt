package com.loopers.fixture

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 거래 수·create 호출 수 관찰, 무작위 지연 없음 */
class TestPaymentProvider : AutoCloseable {
    enum class CreateMode {
        ACCEPT,

        /** 거래를 만들기 전 거절, 효과 0건을 본문으로 보장 */
        REJECT_BEFORE_CREATE,

        /** 효과 0건 보장이 없는 422 */
        REJECT_WITHOUT_BODY,

        /** 거래는 만들고 응답만 늦춰 클라이언트 timeout 유도 */
        ACCEPT_THEN_DELAY,

        SERVER_ERROR,
    }

    data class Transaction(val transactionKey: String, val orderId: String, val amount: Long, val status: String)

    data class Received(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val userId: String?,
        val contentType: String?,
        val body: String,
    )

    private val mapper = ObjectMapper()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(PAYMENTS) { exchange -> handle(exchange) }
        executor = this@TestPaymentProvider.executor
        start()
    }
    private val transactions = CopyOnWriteArrayList<Transaction>()
    private val sequence = AtomicInteger()

    val requests = CopyOnWriteArrayList<Received>()
    val createCount = AtomicInteger()

    @Volatile
    var createMode = CreateMode.ACCEPT

    @Volatile
    var lookupFails = false

    /** 클라이언트 read timeout 보다 길어야 함 */
    var delay: Duration = Duration.ofSeconds(3)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun transactions(): List<Transaction> = transactions.toList()

    fun transactionsOf(orderId: String): List<Transaction> = transactions.filter { it.orderId == orderId }

    /** PG 쪽에서 최종 결과가 정해진 상황 */
    fun complete(transactionKey: String, status: String) {
        val index = transactions.indexOfFirst { it.transactionKey == transactionKey }
        check(index >= 0) { "없는 거래: $transactionKey" }
        transactions[index] = transactions[index].copy(status = status)
    }

    fun reset() {
        transactions.clear()
        requests.clear()
        createCount.set(0)
        createMode = CreateMode.ACCEPT
        lookupFails = false
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().decodeToString()
        requests.add(
            Received(
                method = exchange.requestMethod,
                path = exchange.requestURI.rawPath,
                rawQuery = exchange.requestURI.rawQuery,
                userId = exchange.requestHeaders.getFirst("X-USER-ID"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = body,
            ),
        )
        val path = exchange.requestURI.rawPath.removePrefix(PAYMENTS)
        when {
            exchange.requestMethod == "POST" && path.isEmpty() -> create(exchange, body)
            lookupFails -> respond(exchange, 500, "")
            exchange.requestMethod == "GET" && path.isEmpty() -> {
                val orderId = query(exchange)["orderId"]
                respond(exchange, 200, mapper.writeValueAsString(transactions.filter { it.orderId == orderId }))
            }
            exchange.requestMethod == "GET" -> {
                val key = URLDecoder.decode(path.removePrefix("/"), StandardCharsets.UTF_8)
                transactions.firstOrNull { it.transactionKey == key }
                    ?.let { respond(exchange, 200, mapper.writeValueAsString(it)) }
                    ?: respond(exchange, 404, "")
            }
            else -> respond(exchange, 405, "")
        }
    }

    private fun create(exchange: HttpExchange, body: String) {
        createCount.incrementAndGet()
        val request = mapper.readTree(body)
        val orderId = request.path("orderId").asText()
        val amount = request.path("amount").asLong()
        when (createMode) {
            CreateMode.ACCEPT -> respond(exchange, 202, mapper.writeValueAsString(newTransaction(orderId, amount)))
            CreateMode.ACCEPT_THEN_DELAY -> {
                val created = newTransaction(orderId, amount)
                Thread.sleep(delay.toMillis())
                respond(exchange, 202, mapper.writeValueAsString(created))
            }
            CreateMode.REJECT_BEFORE_CREATE -> respond(
                exchange,
                422,
                mapper.writeValueAsString(
                    mapOf(
                        "code" to "TEST_PG_REJECTED_BEFORE_CREATE",
                        "orderId" to orderId,
                        "amount" to amount,
                        "transactionCreated" to false,
                    ),
                ),
            )
            CreateMode.REJECT_WITHOUT_BODY -> respond(exchange, 422, "")
            CreateMode.SERVER_ERROR -> respond(exchange, 500, "")
        }
    }

    private fun newTransaction(orderId: String, amount: Long): Transaction =
        Transaction(
            transactionKey = "TEST-TX-%04d".format(sequence.incrementAndGet()),
            orderId = orderId,
            amount = amount,
            status = "PENDING",
        ).also { transactions.add(it) }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").filter { it.contains("=") }.associate {
            val (key, value) = it.split("=", limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        runCatching {
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    companion object {
        private const val PAYMENTS = "/api/v1/payments"
    }
}
