package com.loopers.domain.brand

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class BrandService(
    private val brandRepository: BrandRepository,
) {
    @Transactional
    fun create(name: String): Brand = brandRepository.save(Brand(name = name))

    /**
     * 고객 조회와 관리자 수정이 함께 쓴다 (C-1 · A-4).
     *
     * 삭제된 것과 아예 없는 것을 **같은 오류로** 답한다. 구분해 주면 "지워진 브랜드가 있었다"는
     * 사실이 새어나가고, 요청자가 할 수 있는 행동은 어느 쪽이든 같다 — 목록으로 돌아간다.
     */
    @Transactional(readOnly = true)
    fun getAliveOrThrow(id: Long): Brand =
        brandRepository.findAlive(id)
            ?: throw CoreException(ErrorType.BRAND_NOT_FOUND, "[brandId = $id] 브랜드를 찾을 수 없습니다.")

    /** 관리자 상세 (A-3). 삭제된 것도 보여야 "왜 이 브랜드가 안 지워지는지" 판단할 수 있다 (P-33). */
    @Transactional(readOnly = true)
    fun getIncludingDeletedOrThrow(id: Long): Brand =
        brandRepository.findIncludingDeleted(id)
            ?: throw CoreException(ErrorType.BRAND_NOT_FOUND, "[brandId = $id] 브랜드를 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand> = brandRepository.findAllIncludingDeleted(criteria)

    /** 삭제된 브랜드는 대상이 아니다 (P-12). `findAlive` 로 찾으므로 손에 들어오지 않는다. */
    @Transactional
    fun changeName(id: Long, newName: String): Brand = getAliveOrThrow(id).also { it.changeName(newName) }

    /**
     * 논리 삭제 (D-2). 이미 지워진 것을 또 지워도 결과가 같다 —
     * `BaseEntity.delete()` 가 멱등하고, P-12 가 금지한 목록(수정·재고 변경)에 삭제는 없다.
     */
    @Transactional
    fun delete(id: Long) {
        getIncludingDeletedOrThrow(id).delete()
    }
}
