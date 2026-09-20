package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class BrandRepositoryImpl(
    private val brandJpaRepository: BrandJpaRepository,
) : BrandRepository {
    override fun save(brand: Brand): Brand = brandJpaRepository.save(brand)

    /** "삭제되지 않음" 을 SQL 조건으로 내린다. 메모리에서 거르면 페이지 수가 어긋난다. */
    override fun findAlive(id: Long): Brand? = brandJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findIncludingDeleted(id: Long): Brand? = brandJpaRepository.findByIdOrNull(id)

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
