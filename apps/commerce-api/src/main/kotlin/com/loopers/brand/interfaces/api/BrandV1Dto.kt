package com.loopers.brand.interfaces.api

import com.loopers.brand.application.BrandInfo

class BrandV1Dto {
    /** 고객용, id 와 이름만 노출 (삭제·생성 시각은 가림), E2E 가 키 부재 검증 */
    data class BrandResponse(
        val id: Long,
        val name: String,
    ) {
        companion object {
            fun from(info: BrandInfo): BrandResponse = BrandResponse(id = info.id, name = info.name)
        }
    }
}
