package com.loopers.example.domain

interface ExampleRepository {
    fun find(id: Long): ExampleModel?
}
