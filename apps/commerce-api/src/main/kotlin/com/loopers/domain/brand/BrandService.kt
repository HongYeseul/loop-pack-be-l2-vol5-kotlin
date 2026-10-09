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

    /** 삭제된 것과 없는 것을 같은 오류로 응답, 삭제 사실 노출 방지 */
    @Transactional(readOnly = true)
    fun getAliveOrThrow(brandId: Long): Brand =
        brandRepository.findAlive(brandId)
            ?: throw CoreException(ErrorType.BRAND_NOT_FOUND, "[brandId = $brandId] 브랜드를 찾을 수 없습니다.")

    /** 삭제된 브랜드도 조회 */
    @Transactional(readOnly = true)
    fun getIncludingDeletedOrThrow(brandId: Long): Brand =
        brandRepository.findIncludingDeleted(brandId)
            ?: throw CoreException(ErrorType.BRAND_NOT_FOUND, "[brandId = $brandId] 브랜드를 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun getAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand> = brandRepository.findAllIncludingDeleted(criteria)

    /** 한 페이지분을 한 번에 조회, 상품 수만큼 쿼리 방지 */
    @Transactional(readOnly = true)
    fun getAllByIds(brandIds: Collection<Long>): Map<Long, Brand> =
        brandRepository.findAllIncludingDeletedByIds(brandIds.distinct()).associateBy { it.brandId }

    @Transactional
    fun changeName(brandId: Long, newName: String): Brand = getAliveOrThrow(brandId).also { it.changeName(newName) }

    /** 이미 삭제된 것을 다시 삭제해도 결과 동일 */
    @Transactional
    fun delete(brandId: Long) {
        getIncludingDeletedOrThrow(brandId).delete()
    }
}
