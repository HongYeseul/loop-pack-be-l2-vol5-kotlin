package com.loopers

import com.loopers.coupon.Coupon
import com.loopers.purchase.PurchaseActivity
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@ConfigurationPropertiesScan
@EntityScan(basePackageClasses = [Coupon::class, PurchaseActivity::class])
@SpringBootApplication
class CommerceStreamerApplication {

    @PostConstruct
    fun started() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    }
}

fun main(args: Array<String>) {
    runApplication<CommerceStreamerApplication>(*args)
}
