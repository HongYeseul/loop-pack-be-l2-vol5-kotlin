package com.loopers.infrastructure.like

import com.loopers.domain.like.ProductLike
import com.loopers.domain.like.ProductLikeRepository
import org.springframework.stereotype.Component

@Component
class ProductLikeRepositoryImpl(
    private val productLikeJpaRepository: ProductLikeJpaRepository,
) : ProductLikeRepository {
    override fun save(productLike: ProductLike): ProductLike = productLikeJpaRepository.save(productLike)

    override fun exists(userId: Long, productId: Long): Boolean =
        productLikeJpaRepository.existsByUserIdAndProductId(userId = userId, productId = productId)

    /**
     * 찾아서 지운다. 파생 `deleteBy...` 는 **호출부에 트랜잭션이 이미 있어야** 동작하는데,
     * 그러면 저장소가 부르는 쪽의 경계에 기대게 된다. 조회 한 번을 더 하고 그 의존을 없앤다.
     */
    override fun delete(userId: Long, productId: Long) {
        productLikeJpaRepository.findByUserIdAndProductId(userId = userId, productId = productId)
            ?.let { productLikeJpaRepository.delete(it) }
    }

    override fun countByProductId(productId: Long): Long = productLikeJpaRepository.countByProductId(productId)

    /** 빈 목록이면 조회하지 않는다 — `IN ()` 은 SQL 이 되지 않고, 셀 상품도 없다. */
    override fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> {
        if (productIds.isEmpty()) return emptyMap()
        return productLikeJpaRepository.countGroupedPerProduct(productIds.distinct())
            .associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
    }
}
