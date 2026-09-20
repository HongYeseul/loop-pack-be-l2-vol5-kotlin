package com.loopers.application.brand

import com.loopers.domain.brand.BrandService
import com.loopers.domain.product.ProductService
import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component

/**
 * 브랜드와 상품을 **함께 보는 자리** (P-11).
 *
 * `Brand` 는 `Product` 컬렉션을 갖지 않는다 (설계 2-3절). "살아 있는 상품이 연결되어 있나"는
 * 컬렉션을 순회해야 답하는 질문이 아니라 **있냐 없냐**를 묻는 질문이라, 상품 쪽에 물어보면 끝난다.
 * 묻는 일은 두 애그리게잇에 걸쳐 있으므로 어느 도메인의 일도 아니고, 조립하는 쪽의 일이다.
 */
@Component
class BrandFacade(
    private val brandService: BrandService,
    private val productService: ProductService,
) {
    /** C-1 · 고객 상세. 삭제된 브랜드는 없는 것으로 답한다 (P-04 · P-12). */
    fun get(brandId: Long): BrandInfo = BrandInfo.from(brandService.getAliveOrThrow(brandId))

    /**
     * A-3 · 관리자 상세. 삭제된 것도 보인다 (P-33).
     *
     * **연결 상품 수를 함께 준다** (D-10). 이 숫자가 "왜 이 브랜드가 안 지워지는지"(P-11)에 대한 답이다.
     * 거절만 하고 이유를 안 보여주면 관리자는 어느 상품을 먼저 지워야 하는지 알 수 없다.
     */
    fun getForAdmin(brandId: Long): BrandDetailInfo = BrandDetailInfo(
        brand = BrandInfo.from(brandService.getIncludingDeletedOrThrow(brandId)),
        productCount = productService.countAliveByBrand(brandId),
    )

    /** A-1 · 관리자 목록. */
    fun getAllForAdmin(criteria: PageCriteria): PageResult<BrandInfo> =
        brandService.getAllIncludingDeleted(criteria)
            .let { result -> PageResult(result.items.map(BrandInfo::from), result.page, result.size, result.totalCount) }

    /** A-2 · 생성. */
    fun create(name: String): BrandInfo = BrandInfo.from(brandService.create(name))

    /** A-4 · 수정. 삭제된 브랜드는 대상이 아니다 (P-12). */
    fun changeName(brandId: Long, name: String): BrandInfo = BrandInfo.from(brandService.changeName(brandId, name))

    /**
     * A-5 · 논리 삭제 (D-2). **살아 있는 상품이 하나라도 연결되어 있으면 거절한다** (P-11).
     *
     * 세는 기준은 "삭제되지 않았는가" 하나다 — **재고 0 인 상품도, 판매중지·단종된 상품도 연결로 센다**
     * (DS-11). 브랜드 쪽에서 재고 0 은 "아직 살아 있는 상품" 이고, 판매 상태는 삭제와 다른 축이다.
     *
     * 이 규칙이 보장하는 것: **살아 있는 상품의 브랜드는 반드시 살아 있다** (설계 2-4절).
     * 그래서 상품 조회에서 "상품은 있는데 브랜드가 없다"를 고객 오류로 다루지 않는다.
     */
    fun delete(brandId: Long) {
        if (productService.existsAliveByBrand(brandId)) {
            throw CoreException(
                ErrorType.BRAND_HAS_PRODUCTS,
                "[brandId = $brandId] 연결된 상품이 남아 있습니다. 상품을 먼저 삭제해주세요.",
            )
        }
        brandService.delete(brandId)
    }
}
