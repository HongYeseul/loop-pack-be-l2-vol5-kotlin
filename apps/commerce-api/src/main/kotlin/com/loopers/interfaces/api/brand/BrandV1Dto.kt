package com.loopers.interfaces.api.brand

import com.loopers.application.brand.BrandInfo

class BrandV1Dto {
    /**
     * 고객이 보는 브랜드 — **id 와 이름뿐**입니다 (D-10).
     *
     * `BrandInfo` 는 삭제 시각과 생성 시각도 들고 있지만 여기서 가려냅니다.
     * 구조로 막지 못하는 자리라 E2E 테스트가 "이 키들이 없다"를 확인합니다 (DS-5).
     */
    data class BrandResponse(
        val id: Long,
        val name: String,
    ) {
        companion object {
            fun from(info: BrandInfo): BrandResponse = BrandResponse(id = info.id, name = info.name)
        }
    }
}
