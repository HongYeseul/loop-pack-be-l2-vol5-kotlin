package com.loopers.domain.brand

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult

/**
 * **`findById` 를 두지 않는다** (DS-3).
 *
 * 브랜드는 논리 삭제 대상이라 모든 조회에 "삭제되지 않음" 조건이 따라붙는데,
 * 한 군데라도 빠뜨리면 삭제된 브랜드가 고객에게 노출된다(P-12).
 * 조건을 이름이 들고 있으면 부르는 쪽이 **어느 질문인지 고르지 않을 수 없다**.
 */
interface BrandRepository {
    fun save(brand: Brand): Brand

    /** 고객의 질문 — 삭제되지 않은 것만 (P-04). */
    fun findAlive(brandId: Long): Brand?

    /** 관리자의 질문 — 삭제된 것도 본다 (P-33). */
    fun findIncludingDeleted(brandId: Long): Brand?

    /**
     * 삭제된 것을 포함한 목록 (A-1).
     *
     * **`id` 내림차순으로 돌려준다.** 정렬 기준이 없으면 DB 가 페이지마다 다른 순서를 볼 수 있고,
     * 그러면 어떤 항목은 두 페이지에 나오고 어떤 항목은 한 번도 안 나온다 (기획 D-3).
     * `id` 는 유일하므로 동점이 아예 없어진다.
     */
    fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand>

    /**
     * 여러 브랜드를 한 번에 읽는다 — 상품 목록에 브랜드 이름을 붙일 때 쓴다 (C-2 · A-6 · DS-1).
     *
     * **삭제된 것도 읽는다.** P-11 이 "살아 있는 상품의 브랜드는 반드시 살아 있다"를 보장하므로
     * 고객 목록에서는 삭제된 브랜드가 나올 수 없고, 관리자 목록에는 삭제된 상품이 나오므로
     * 그 브랜드도 읽혀야 한다 (P-33). 여기서 거르면 이름이 빈 상품이 생긴다.
     */
    fun findAllIncludingDeletedByIds(brandIds: Collection<Long>): List<Brand>
}
