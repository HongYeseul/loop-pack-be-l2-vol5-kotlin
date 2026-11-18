package com.loopers.example.domain

import com.loopers.example.ExampleModel

interface ExampleRepository {
    fun find(id: Long): ExampleModel?
}
