package com.loopers.example.infrastructure

import com.loopers.example.domain.ExampleModel
import org.springframework.data.jpa.repository.JpaRepository

interface ExampleJpaRepository : JpaRepository<ExampleModel, Long>
