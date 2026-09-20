package com.loopers.application.product

import com.loopers.domain.admin.AdminLoginId
import com.loopers.domain.admin.AdminPermission
import com.loopers.domain.admin.AdminUserService
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandService
import com.loopers.domain.like.ProductLikeService
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductListCriteria
import com.loopers.domain.product.ProductService
import com.loopers.domain.product.ProductStatus
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.domain.user.LoginId
import com.loopers.domain.user.UserService
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component

/**
 * 상품에 **브랜드 이름과 좋아요 수를 붙이는 자리** (DS-1).
 *
 * `Product` 는 `brandId` 만 들고 `Brand` 는 상품을 모르며(설계 2-2절 · 2-3절) 좋아요 수는
 * 관계에서 세는 값입니다(P-15). 셋을 잇는 일은 어느 도메인의 일도 아닙니다.
 * 목록은 한 페이지 분량을 모아 **한 번에** 읽습니다 — 상품 수만큼 조회하지 않습니다.
 */
@Component
class ProductFacade(
    private val productService: ProductService,
    private val brandService: BrandService,
    private val adminUserService: AdminUserService,
    private val productLikeService: ProductLikeService,
    private val userService: UserService,
) {
    /** C-2 · 고객 목록. 삭제·판매중지·단종은 빠집니다 (P-12 · P-39). */
    fun getAll(criteria: ProductListCriteria): PageResult<ProductInfo> =
        productService.getAliveProducts(criteria).toInfos()

    /**
     * C-6 · 내 좋아요 목록 (P-45). 상품 목록이라 조립이 [getAll] 과 같습니다 — 다른 것은 **거르는 조건**뿐입니다.
     *
     * 경로의 사용자가 요청자와 다르면 **없는 대상**으로 답합니다 (P-02). 권한 오류로 답하면
     * "그 사용자는 있다" 가 새어나가고, 요청자가 할 수 있는 일은 어느 쪽이든 같습니다.
     *
     * 요청자 확인이 먼저입니다 — 차단된 계정은 자기 목록도 볼 수 없습니다 (P-42).
     */
    fun getLikedProducts(loginId: LoginId, targetUserId: String, page: PageCriteria): PageResult<ProductInfo> {
        val user = userService.getActiveOrThrow(loginId)
        if (targetUserId != loginId.value) {
            throw CoreException(ErrorType.USER_NOT_FOUND, "[userId = $targetUserId] 사용자를 찾을 수 없습니다.")
        }
        return productService.getAliveProductsLikedBy(user.userId, page).toInfos()
    }

    /** A-6 · 관리자 목록. 삭제된 것도 보입니다 (P-33). */
    fun getAllForAdmin(requester: AdminLoginId, criteria: PageCriteria): PageResult<ProductInfo> {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_READ)
        return productService.getAllIncludingDeleted(criteria).toInfos()
    }

    /** C-3 · 고객 상세. 판매중지·단종도 보입니다 — 목록에서만 빠집니다 (P-39). */
    fun get(productId: Long): ProductInfo = productService.getAliveOrThrow(productId).toInfo()

    /** A-8 · 관리자 상세. 삭제 시각까지 보입니다 (P-33). */
    fun getForAdmin(requester: AdminLoginId, productId: Long): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_READ)
        return productService.getIncludingDeletedOrThrow(productId).toInfo()
    }

    /**
     * A-7 · 생성. **살아 있는 브랜드인지 여기서 확인합니다** (P-05) —
     * `getAliveOrThrow` 가 거절하므로 지워진 브랜드에 상품이 붙는 일이 없습니다.
     */
    fun create(requester: AdminLoginId, brandId: Long, name: String, price: Long, stock: Int): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        val brand = brandService.getAliveOrThrow(brandId)
        // 방금 만든 상품이라 관계가 있을 수 없다. 세어 봐야 0 이다 (P-15)
        return ProductInfo.of(productService.create(brandId, name, price, stock), brand, likeCount = 0L)
    }

    /** A-9 · 수정. 이름과 가격만 바뀝니다. **브랜드는 못 바꿉니다** (P-05). */
    fun changeNameAndPrice(requester: AdminLoginId, productId: Long, name: String, price: Long): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return productService.changeNameAndPrice(productId, name, price).toInfo()
    }

    /** A-11 · 재고 설정 (P-07). 증감이 아니라 최종 수량입니다. */
    fun changeStock(requester: AdminLoginId, productId: Long, quantity: Int): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return productService.changeStock(productId, quantity).toInfo()
    }

    /** A-16 · 판매 상태 설정 (P-36). 단종은 되돌릴 수 없습니다. */
    fun changeStatus(requester: AdminLoginId, productId: Long, status: ProductStatus): ProductInfo {
        adminUserService.requirePermission(requester, AdminPermission.CATALOG_WRITE)
        return productService.changeStatus(productId, status).toInfo()
    }

    /** A-10 · 논리 삭제 (D-2). */
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

    /**
     * 단건 경로는 **삭제된 브랜드도 읽습니다** — P-11 이 "살아 있는 상품의 브랜드는 반드시 살아 있다"를
     * 보장하므로 고객 경로에서는 나올 수 없고, 관리자는 삭제된 상품을 보니 그 브랜드도 보여야 합니다 (P-33).
     */
    private fun brandOf(brandId: Long): Brand =
        brandService.getAllByIds(listOf(brandId)).requireBrand(brandId)

    /**
     * 브랜드 행이 아예 없는 것은 **요청자가 고칠 수 있는 일이 아닙니다** (설계 2-4절).
     * P-11 의 불변식이 깨진 상태라 없는 대상 오류가 아니라 내부 오류로 봅니다.
     */
    private fun Map<Long, Brand>.requireBrand(brandId: Long): Brand =
        this[brandId] ?: throw CoreException(
            ErrorType.INTERNAL_ERROR,
            "[brandId = $brandId] 상품이 가리키는 브랜드가 없습니다. (P-11 불변식 위반)",
        )
}
