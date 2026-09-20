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
    fun findAlive(id: Long): Brand?

    /** 관리자의 질문 — 삭제된 것도 본다 (P-33). */
    fun findIncludingDeleted(id: Long): Brand?

    /**
     * 삭제된 것을 포함한 목록 (A-1).
     *
     * **`id` 내림차순으로 돌려준다.** 정렬 기준이 없으면 DB 가 페이지마다 다른 순서를 볼 수 있고,
     * 그러면 어떤 항목은 두 페이지에 나오고 어떤 항목은 한 번도 안 나온다 (기획 D-3).
     * `id` 는 유일하므로 동점이 아예 없어진다.
     */
    fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand>
}
