package com.loopers.fixture

import com.loopers.domain.brand.Brand

object BrandFixture {
    const val DEFAULT_NAME = "루퍼스"

    fun brand(name: String = DEFAULT_NAME): Brand = Brand(name = name)

    /** 논리 삭제된 브랜드 (D-2). 고객 조회에서 제외되는지 확인할 때 쓴다. */
    fun deletedBrand(name: String = DEFAULT_NAME): Brand = brand(name).apply { delete() }
}
