package com.loopers.application.brand

import com.loopers.domain.brand.BrandService
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import org.springframework.stereotype.Component

@Component
class BrandFacade(
    private val brandService: BrandService,
) {
    /** C-1 · 고객 상세. 삭제된 브랜드는 없는 것으로 답한다 (P-04 · P-12). */
    fun get(brandId: Long): BrandInfo = BrandInfo.from(brandService.getAliveOrThrow(brandId))

    /** A-3 · 관리자 상세. 삭제된 것도 보인다 (P-33). */
    fun getForAdmin(brandId: Long): BrandInfo = BrandInfo.from(brandService.getIncludingDeletedOrThrow(brandId))

    /** A-1 · 관리자 목록. */
    fun getAllForAdmin(criteria: PageCriteria): PageResult<BrandInfo> =
        brandService.getAllIncludingDeleted(criteria)
            .let { result -> PageResult(result.items.map(BrandInfo::from), result.page, result.size, result.totalCount) }

    /** A-2 · 생성. */
    fun create(name: String): BrandInfo = BrandInfo.from(brandService.create(name))

    /** A-4 · 수정. 삭제된 브랜드는 대상이 아니다 (P-12). */
    fun changeName(brandId: Long, name: String): BrandInfo = BrandInfo.from(brandService.changeName(brandId, name))

    /** A-5 · 논리 삭제 (D-2). */
    fun delete(brandId: Long) = brandService.delete(brandId)
}
