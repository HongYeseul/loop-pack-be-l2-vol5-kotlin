package com.loopers.example.application

import com.loopers.example.domain.ExampleModel

data class ExampleInfo(
    val id: Long,
    val name: String,
    val description: String,
) {
    companion object {
        fun from(model: ExampleModel): ExampleInfo {
            return ExampleInfo(
                id = model.id,
                name = model.name,
                description = model.description,
            )
        }
    }
}
