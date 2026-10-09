package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import java.time.ZonedDateTime

/** 보는 사람별 가림은 interfaces DTO 담당 */
data class BrandInfo(
    val id: Long,
    val name: String,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
    val deletedAt: ZonedDateTime?,
) {
    companion object {
        fun from(brand: Brand): BrandInfo = BrandInfo(
            id = brand.brandId,
            name = brand.name,
            createdAt = brand.createdAt,
            updatedAt = brand.updatedAt,
            deletedAt = brand.deletedAt,
        )
    }
}
