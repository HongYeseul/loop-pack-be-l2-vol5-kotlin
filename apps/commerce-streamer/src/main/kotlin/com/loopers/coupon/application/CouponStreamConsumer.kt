package com.loopers.coupon.application

import com.loopers.coupon.domain.CouponCampaignRepository
import com.loopers.coupon.stream.domain.CouponExecutionGate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(prefix = "coupon.consumer", name = ["enabled"], havingValue = "true")
class CouponStreamConsumer(
    private val campaigns: CouponCampaignRepository,
    private val gate: CouponExecutionGate,
    private val processor: CouponStreamProcessor,
    @Value("\${coupon.consumer.group:coupon-issuers}") private val group: String,
    @Value("\${coupon.consumer.concurrency:3}") private val concurrency: Int,
    @Value("\${coupon.consumer.poll-delay-ms:200}") private val delay: Long,
    @Value("\${coupon.consumer.claim-idle-ms:30000}") private val claimIdle: Long,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val owner = UUID.randomUUID().toString()
    private var pool: ScheduledExecutorService? = null

    init {
        require(concurrency > 0 && delay > 0 && claimIdle > 0)
    }

    fun processOne(campaignId: Long, consumer: String = "$owner:manual"): Boolean {
        return processor.processOne(campaignId, group, consumer, claimIdle)
    }

    private fun poll(consumer: String) {
        runCatching {
            if (gate.status().accepting) campaigns.findAll().forEach { processOne(it.id, consumer) }
        }.onFailure { log.error("쿠폰 발급 미완료 작업 유지 consumer={}", consumer, it) }
    }

    @Synchronized
    override fun start() {
        if (pool != null) return
        pool = Executors.newScheduledThreadPool(concurrency).also { executor ->
            repeat(concurrency) { index ->
                executor.scheduleWithFixedDelay({ poll("$owner:$index") }, 0, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    @Synchronized
    override fun stop() {
        pool?.let { executor ->
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
        pool = null
    }

    override fun isRunning(): Boolean = pool?.isShutdown == false
    override fun getPhase(): Int = Int.MAX_VALUE
}
