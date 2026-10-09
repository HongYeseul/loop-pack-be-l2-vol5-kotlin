package com.loopers.domain.product

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import java.time.ZonedDateTime

/** findById 없음, 삭제 조건을 메서드 이름에 담아 누락 방지 */
interface ProductRepository {
    fun save(product: Product): Product

    /** 삭제되지 않은 상품, 판매중지·단종은 상세 조회 가능이라 거르지 않음 */
    fun findAlive(id: Long): Product?

    /** 삭제·없는 상품은 조용히 제외, 개수 비교는 호출부 책임 */
    fun findAliveAll(ids: Collection<Long>): List<Product>

    fun findIncludingDeleted(id: Long): Product?

    /** 이름·상태 수정도 행 전체를 UPDATE 하므로 잠금, 재고를 옛 값으로 덮어쓰는 것 방지 */
    fun findAliveForUpdate(id: Long): Product?

    fun findIncludingDeletedForUpdate(id: Long): Product?

    /** id 오름차순으로 잠금, 삭제·없는 상품은 조용히 제외 */
    fun findAliveAllForUpdate(ids: Collection<Long>): List<Product>

    /** 정렬 뒤 id 내림차순 필수 (동점 구간 페이지 중복·누락 방지) */
    fun findAliveProducts(criteria: ProductListCriteria): PageResult<Product>

    /** 삭제된 상품만 제외 (판매중지·단종은 유지), 최근 좋아요 순 */
    fun findAliveProductsLikedBy(userId: Long, page: PageCriteria): PageResult<Product>

    /** 삭제된 것 포함, id 내림차순 */
    fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Product>

    /** 재고 0·판매중지·단종도 연결로 계산 */
    fun countAliveByBrandId(brandId: Long): Long

    fun deleteAliveByBrandId(brandId: Long, now: ZonedDateTime)
}
