package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import java.time.ZonedDateTime

/**
 * `application` 은 **누가 보는지 모릅니다** (DS-5).
 *
 * 필요한 값을 전부 담고, 고객에게 보일 것과 관리자에게 보일 것은 `interfaces` 의 DTO 가 가려냅니다.
 * 조립 코드를 한 군데로 모으는 대신, "고객 응답에 `deletedAt` 이 없다"는 테스트로 경계를 지킵니다.
 */
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
