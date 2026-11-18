package com.loopers

import com.loopers.outbox.OutboxEvent
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@ConfigurationPropertiesScan
@EntityScan(basePackageClasses = [OutboxEvent::class])
@SpringBootApplication
class CommerceRelayApplication {

    @PostConstruct
    fun started() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    }
}

fun main(args: Array<String>) {
    runApplication<CommerceRelayApplication>(*args)
}
