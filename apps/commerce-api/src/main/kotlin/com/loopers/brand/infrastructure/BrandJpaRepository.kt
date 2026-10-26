package com.loopers.brand.infrastructure

import com.loopers.brand.domain.Brand
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface BrandJpaRepository : JpaRepository<Brand, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Brand?

    fun findAllByOrderByIdDesc(pageable: Pageable): Page<Brand>
}
