package com.loopers.brand.infrastructure

import com.loopers.brand.domain.Brand
import com.loopers.brand.domain.BrandRepository
import com.loopers.support.domain.PageCriteria
import com.loopers.support.domain.PageResult
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class BrandRepositoryImpl(
    private val brandJpaRepository: BrandJpaRepository,
) : BrandRepository {
    override fun save(brand: Brand): Brand = brandJpaRepository.save(brand)

    override fun findAlive(brandId: Long): Brand? = brandJpaRepository.findByIdAndDeletedAtIsNull(brandId)

    override fun findIncludingDeleted(brandId: Long): Brand? = brandJpaRepository.findByIdOrNull(brandId)

    override fun findAllIncludingDeletedByIds(brandIds: Collection<Long>): List<Brand> = brandJpaRepository.findAllById(brandIds)

    override fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand> {
        val page = brandJpaRepository.findAllByOrderByIdDesc(PageRequest.of(criteria.page, criteria.size))
        return PageResult(
            items = page.content,
            page = criteria.page,
            size = criteria.size,
            totalCount = page.totalElements,
        )
    }
}
