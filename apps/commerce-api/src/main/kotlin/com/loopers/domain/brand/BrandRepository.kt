package com.loopers.domain.brand

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult

/** findById 없음, 삭제 조건을 메서드 이름에 담아 누락 방지 */
interface BrandRepository {
    fun save(brand: Brand): Brand

    fun findAlive(brandId: Long): Brand?

    fun findIncludingDeleted(brandId: Long): Brand?

    /** id 내림차순 고정, 정렬 기준이 없으면 페이지 간 중복·누락 발생 */
    fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand>

    /** 삭제된 브랜드도 조회, 관리자 목록의 삭제된 상품에 브랜드명 필요 */
    fun findAllIncludingDeletedByIds(brandIds: Collection<Long>): List<Brand>
}
