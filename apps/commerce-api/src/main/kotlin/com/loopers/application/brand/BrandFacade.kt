package com.loopers.application.brand

import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminPermission
import com.loopers.domain.admin.AdminUserService
import com.loopers.domain.brand.BrandService
import com.loopers.domain.product.ProductService
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component

/** 브랜드와 상품을 함께 보는 자리, Brand 는 Product 컬렉션이 없어 상품 쪽에 조회 */
@Component
class BrandFacade(
    private val brandService: BrandService,
    private val productService: ProductService,
    private val adminUserService: AdminUserService,
) {
    fun get(brandId: Long): BrandInfo = BrandInfo.from(brandService.getAliveOrThrow(brandId))

    /** 삭제된 브랜드도 조회, 연결 상품 수 함께 제공 (삭제 불가 사유 확인용) */
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

    /** 살아 있는 상품이 하나라도 연결되면 삭제 거절 (재고 0·판매중지·단종 포함) */
    fun delete(requester: AdminLoginId, brandId: Long) {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        if (productService.existsAliveByBrand(brandId)) {
            throw CoreException(
                ErrorType.BRAND_HAS_PRODUCTS,
                "[brandId = $brandId] 연결된 상품이 남아 있습니다. 상품을 먼저 삭제해주세요.",
            )
        }
        brandService.delete(brandId)
    }
}
