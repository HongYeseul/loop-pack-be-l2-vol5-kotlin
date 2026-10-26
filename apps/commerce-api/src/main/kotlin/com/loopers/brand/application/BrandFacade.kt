package com.loopers.brand.application

import com.loopers.admin.domain.AdminLoginId
import com.loopers.admin.domain.AdminPermission
import com.loopers.admin.domain.AdminUserService
import com.loopers.brand.domain.BrandService
import com.loopers.product.domain.ProductService
import com.loopers.support.domain.PageCriteria
import com.loopers.support.domain.PageResult
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 브랜드와 상품을 함께 보는 자리, Brand 는 Product 컬렉션이 없어 상품 쪽에 조회 */
@Component
class BrandFacade(
    private val brandService: BrandService,
    private val productService: ProductService,
    private val adminUserService: AdminUserService,
) {
    fun get(brandId: Long): BrandInfo = BrandInfo.from(brandService.getAliveOrThrow(brandId))

    /** 삭제된 브랜드도 조회, 연결 상품 수 함께 제공 */
    fun getForAdmin(requester: AdminLoginId, brandId: Long): BrandDetailInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_READ)
        return BrandDetailInfo(
            brand = BrandInfo.from(brandService.getIncludingDeletedOrThrow(brandId)),
            productCount = productService.countAliveByBrand(brandId),
        )
    }

    fun getAllForAdmin(requester: AdminLoginId, criteria: PageCriteria): PageResult<BrandInfo> {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_READ)
        return brandService.getAllIncludingDeleted(criteria)
            .let { result -> PageResult(result.items.map(BrandInfo::from), result.page, result.size, result.totalCount) }
    }

    fun create(requester: AdminLoginId, name: String): BrandInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return BrandInfo.from(brandService.create(name))
    }

    fun changeName(requester: AdminLoginId, brandId: Long, name: String): BrandInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return BrandInfo.from(brandService.changeName(brandId, name))
    }

    /** 상품 일괄 삭제가 영속성 컨텍스트를 비우므로 브랜드는 그 뒤에 다시 읽어 삭제 */
    @Transactional
    fun delete(requester: AdminLoginId, brandId: Long) {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        brandService.getIncludingDeletedOrThrow(brandId)
        productService.deleteAliveByBrand(brandId)
        brandService.delete(brandId)
    }
}
