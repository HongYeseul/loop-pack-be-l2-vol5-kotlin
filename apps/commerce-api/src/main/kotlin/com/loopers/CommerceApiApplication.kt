package com.loopers

import com.loopers.admin.AdminUser
import com.loopers.brand.Brand
import com.loopers.coupon.Coupon
import com.loopers.example.ExampleModel
import com.loopers.like.ProductLike
import com.loopers.order.Order
import com.loopers.outbox.OutboxEvent
import com.loopers.payment.PaymentAttempt
import com.loopers.point.Point
import com.loopers.product.Product
import com.loopers.user.User
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@ConfigurationPropertiesScan
@EntityScan(
    basePackageClasses = [
        AdminUser::class,
        Brand::class,
        Coupon::class,
        ExampleModel::class,
        ProductLike::class,
        Order::class,
        OutboxEvent::class,
        PaymentAttempt::class,
        Point::class,
        Product::class,
        User::class,
    ],
)
@SpringBootApplication
class CommerceApiApplication {

    @PostConstruct
    fun started() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    }
}

fun main(args: Array<String>) {
    runApplication<CommerceApiApplication>(*args)
}
