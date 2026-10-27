package com.loopers.product.application

import com.loopers.admin.domain.AdminLoginId
import com.loopers.admin.domain.AdminPermission
import com.loopers.admin.domain.AdminUserService
import com.loopers.brand.domain.Brand
import com.loopers.brand.domain.BrandService
import com.loopers.like.domain.ProductLikeService
import com.loopers.product.domain.Product
import com.loopers.product.domain.ProductListCriteria
import com.loopers.product.domain.ProductService
import com.loopers.product.domain.ProductStatus
import com.loopers.support.domain.PageCriteria
import com.loopers.support.domain.PageResult
import com.loopers.user.domain.LoginId
import com.loopers.user.domain.UserService
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component

/** 상품에 브랜드 이름·좋아요 수를 붙임, 한 페이지를 모아 한 번에 조회 (상품 수만큼 쿼리 방지) */
@Component
class ProductFacade(
    private val productService: ProductService,
    private val brandService: BrandService,
    private val adminUserService: AdminUserService,
    private val productLikeService: ProductLikeService,
    private val userService: UserService,
) {
    /** 삭제·판매중지·단종 제외 */
    fun getAll(criteria: ProductListCriteria): PageResult<ProductInfo> =
        productService.getAliveProducts(criteria).toInfos()

    /**
     * 경로의 사용자가 요청자와 다르면 없는 대상으로 응답 (권한 오류는 사용자 존재 노출)
     * 요청자 확인이 먼저, 차단된 계정은 자기 목록도 조회 불가
     */
    fun getLikedProducts(loginId: LoginId, targetUserId: String, page: PageCriteria): PageResult<ProductInfo> {
        val user = userService.getActiveOrThrow(loginId)
        if (targetUserId != loginId.value) {
            throw CoreException(ErrorType.USER_NOT_FOUND, "[userId = $targetUserId] 사용자를 찾을 수 없습니다.")
        }
        return productService.getAliveProductsLikedBy(user.userId, page).toInfos()
    }

    fun getAllForAdmin(requester: AdminLoginId, criteria: PageCriteria): PageResult<ProductInfo> {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_READ)
        return productService.getAllIncludingDeleted(criteria).toInfos()
    }

    /** 판매중지·단종도 조회 가능, 목록에서만 제외 */
    fun get(productId: Long): ProductInfo {
        val info = productService.getAliveOrThrow(productId).toInfo()
        // TODO: 조회 성공 후 ProductDetailQueried를 product-events로 발행 연결 (실제 화면 노출과 구분)
        return info
    }

    fun getForAdmin(requester: AdminLoginId, productId: Long): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_READ)
        return productService.getIncludingDeletedOrThrow(productId).toInfo()
    }

    /** 살아 있는 브랜드인지 확인, 삭제된 브랜드에 상품 연결 방지 */
    fun create(requester: AdminLoginId, brandId: Long, name: String, price: Long, stock: Int): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        val brand = brandService.getAliveOrThrow(brandId)
        // 방금 만든 상품이라 좋아요 0
        return ProductInfo.of(productService.create(brandId, name, price, stock), brand, likeCount = 0L)
    }

    /** 브랜드 변경 불가 */
    fun changeNameAndPrice(requester: AdminLoginId, productId: Long, name: String, price: Long): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return productService.changeNameAndPrice(productId, name, price).toInfo()
    }

    /** 증감이 아닌 최종 수량 */
    fun changeStock(requester: AdminLoginId, productId: Long, quantity: Int): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return productService.changeStock(productId, quantity).toInfo()
    }

    /** 단종은 되돌릴 수 없음 */
    fun changeStatus(requester: AdminLoginId, productId: Long, status: ProductStatus): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return productService.changeStatus(productId, status).toInfo()
    }

    fun delete(requester: AdminLoginId, productId: Long) {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        productService.delete(productId)
    }

    private fun Product.toInfo(): ProductInfo =
        ProductInfo.of(this, brandOf(brandId), productLikeService.countByProductId(productId))

    private fun PageResult<Product>.toInfos(): PageResult<ProductInfo> {
        val brands = brandService.getAllByIds(items.map { it.brandId })
        val likeCounts = productLikeService.countByProductIds(items.map { it.productId })
        return PageResult(
            items = items.map { ProductInfo.of(it, brands.requireBrand(it.brandId), likeCounts[it.productId] ?: 0L) },
            page = page,
            size = size,
            totalCount = totalCount,
        )
    }

    /** 삭제된 브랜드도 조회 (관리자는 삭제된 상품을 보므로 브랜드도 필요) */
    private fun brandOf(brandId: Long): Brand =
        brandService.getAllByIds(listOf(brandId)).requireBrand(brandId)

    /** 브랜드 행이 없으면 내부 오류 (요청자가 고칠 수 없는 불변식 위반) */
    private fun Map<Long, Brand>.requireBrand(brandId: Long): Brand =
        this[brandId] ?: throw CoreException(
            ErrorType.INTERNAL_ERROR,
            "[brandId = $brandId] 상품이 가리키는 브랜드가 없습니다. (불변식 위반)",
        )
}
