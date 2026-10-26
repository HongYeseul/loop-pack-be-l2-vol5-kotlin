package com.loopers.fixture

import com.loopers.brand.domain.Brand

object BrandFixture {
    const val DEFAULT_NAME = "루퍼스"

    fun brand(name: String = DEFAULT_NAME): Brand = Brand(name = name)

    /** 논리 삭제된 브랜드, 고객 조회 제외 확인용 */
    fun deletedBrand(name: String = DEFAULT_NAME): Brand = brand(name).apply { delete() }
}
