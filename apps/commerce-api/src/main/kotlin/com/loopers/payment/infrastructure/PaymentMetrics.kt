package com.loopers.payment.infrastructure

import com.loopers.payment.PaymentStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class PaymentMetrics {
    @Bean
    fun unknownPaymentAttempts(repository: PaymentAttemptJpaRepository): MeterBinder = MeterBinder { registry ->
        Gauge.builder("commerce.payment.unknown", repository) { it.countByStatus(PaymentStatus.UNKNOWN).toDouble() }
            .description("현재 UNKNOWN 상태인 결제 시도 수")
            .register(registry)
    }
}
