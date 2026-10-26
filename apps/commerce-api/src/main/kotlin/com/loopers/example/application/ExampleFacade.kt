package com.loopers.example.application

import com.loopers.example.domain.ExampleService
import org.springframework.stereotype.Component

@Component
class ExampleFacade(
    private val exampleService: ExampleService,
) {
    fun getExample(id: Long): ExampleInfo {
        return exampleService.getExample(id)
            .let { ExampleInfo.from(it) }
    }
}
