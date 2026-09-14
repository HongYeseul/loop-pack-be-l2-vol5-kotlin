package com.loopers.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class AdminBoundaryConfig {
    @Bean
    fun adminBoundary(http: HttpSecurity): SecurityFilterChain =
        http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests { it.anyRequest().hasRole("ADMIN") }
            .exceptionHandling { errors ->
                errors.authenticationEntryPoint { _, response, _ -> response.sendError(403) }
            }
            .build()
}
