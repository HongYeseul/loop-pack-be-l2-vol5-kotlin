package com.loopers.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "coupon.operations", name = ["enabled"], havingValue = "true")
class LocalCouponOperationsSecurity {
    @Bean
    @Order(0)
    fun localCouponOperations(
        http: HttpSecurity,
        @Value("\${coupon.operations.username}") username: String,
        @Value("\${coupon.operations.password}") password: String,
    ): SecurityFilterChain {
        require(username.isNotBlank() && password.isNotBlank()) { "로컬 쿠폰 운영자 자격 증명을 설정해주세요." }
        val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
        val operator = User.withUsername(username).password(encoder.encode(password)).roles("COUPON_OPERATOR").build()
        return http.securityMatcher("/api-admin/v1/local/coupon-admission", "/api-admin/v1/local/coupon-admission/**")
            .csrf { it.disable() }
            .userDetailsService(InMemoryUserDetailsManager(operator))
            .authorizeHttpRequests { it.anyRequest().hasRole("COUPON_OPERATOR") }
            .httpBasic { }
            .build()
    }
}
