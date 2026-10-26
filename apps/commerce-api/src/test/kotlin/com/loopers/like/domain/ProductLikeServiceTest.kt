package com.loopers.like.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * 가짜 저장소가 UNIQUE(user_id, product_id) 를 흉내 (서비스의 중복 검사 누락을 드러냄)
 * 실제 제약은 ProductLikeRepositoryIntegrationTest 담당
 */
class ProductLikeServiceTest {
    private class FakeProductLikeRepository : ProductLikeRepository {
        private val stored = linkedMapOf<Pair<Long, Long>, ProductLike>()

        override fun save(productLike: ProductLike): ProductLike {
            val key = productLike.userId to productLike.productId
            check(key !in stored) { "UNIQUE(user_id, product_id) 위반" }
            stored[key] = productLike
            return productLike
        }

        override fun exists(userId: Long, productId: Long): Boolean = (userId to productId) in stored

        override fun delete(userId: Long, productId: Long) {
            stored.remove(userId to productId)
        }

        override fun countByProductId(productId: Long): Long = stored.keys.count { it.second == productId }.toLong()

        /** 좋아요 없는 상품은 키 없음, 호출부에서 0 으로 처리 */
        override fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> =
            stored.keys
                .filter { it.second in productIds }
                .groupingBy { it.second }
                .eachCount()
                .mapValues { it.value.toLong() }

        val rowCount: Int get() = stored.size
    }

    private val productLikeRepository = FakeProductLikeRepository()
    private val productLikeService = ProductLikeService(productLikeRepository)

    @DisplayName("좋아요를 등록할 때,")
    @Nested
    inner class Like {
        @DisplayName("처음이면, 관계가 하나 생긴다.")
        @Test
        fun createsRelation() {
            // act
            productLikeService.like(userId = 1L, productId = 10L)

            // assert
            assertAll(
                { assertThat(productLikeRepository.exists(userId = 1L, productId = 10L)).isTrue() },
                { assertThat(productLikeRepository.countByProductId(10L)).isEqualTo(1L) },
            )
        }

        @DisplayName("같은 요청을 두 번 보내도, 행이 늘지 않는다.")
        @Test
        fun isIdempotent() {
            // act
            productLikeService.like(userId = 1L, productId = 10L)
            assertDoesNotThrow { productLikeService.like(userId = 1L, productId = 10L) }

            // assert
            assertThat(productLikeRepository.rowCount).isEqualTo(1)
        }

        @DisplayName("사용자와 상품을 뒤집어 넣지 않는다.")
        @Test
        fun storesUserAndProductInOrder() {
            // act
            productLikeService.like(userId = 1L, productId = 10L)

            // assert
            assertAll(
                { assertThat(productLikeRepository.exists(userId = 1L, productId = 10L)).isTrue() },
                { assertThat(productLikeRepository.exists(userId = 10L, productId = 1L)).isFalse() },
            )
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    inner class Unlike {
        @DisplayName("걸려 있으면, 행이 사라진다.")
        @Test
        fun removesRelation() {
            // arrange
            productLikeService.like(userId = 1L, productId = 10L)

            // act
            productLikeService.unlike(userId = 1L, productId = 10L)

            // assert
            assertAll(
                { assertThat(productLikeRepository.exists(userId = 1L, productId = 10L)).isFalse() },
                { assertThat(productLikeRepository.rowCount).isEqualTo(0) },
            )
        }

        @DisplayName("없는 관계를 취소해도, 성공이다.")
        @Test
        fun isIdempotent() {
            // act & assert
            assertDoesNotThrow { productLikeService.unlike(userId = 1L, productId = 10L) }
            assertThat(productLikeRepository.rowCount).isEqualTo(0)
        }

        @DisplayName("남의 관계는 건드리지 않는다.")
        @Test
        fun leavesOtherUsersRelation() {
            // arrange
            productLikeService.like(userId = 1L, productId = 10L)
            productLikeService.like(userId = 2L, productId = 10L)

            // act
            productLikeService.unlike(userId = 1L, productId = 10L)

            // assert
            assertThat(productLikeRepository.exists(userId = 2L, productId = 10L)).isTrue()
        }
    }

    @DisplayName("좋아요 수를 셀 때,")
    @Nested
    inner class CountByProduct {
        @DisplayName("목록 한 페이지의 좋아요 수를 한 번에 센다. 아무도 안 누른 상품은 빠진다.")
        @Test
        fun countsManyProductsAtOnce() {
            // arrange
            productLikeService.like(userId = 1L, productId = 10L)
            productLikeService.like(userId = 2L, productId = 10L)
            productLikeService.like(userId = 1L, productId = 20L)

            // act
            val counts = productLikeService.countByProductIds(listOf(10L, 20L, 30L))

            // assert
            assertAll(
                { assertThat(counts[10L]).isEqualTo(2L) },
                { assertThat(counts[20L]).isEqualTo(1L) },
                { assertThat(counts).doesNotContainKey(30L) },
            )
        }

        @DisplayName("상품마다 따로 센다.")
        @Test
        fun countsPerProduct() {
            // arrange
            productLikeService.like(userId = 1L, productId = 10L)
            productLikeService.like(userId = 2L, productId = 10L)
            productLikeService.like(userId = 1L, productId = 20L)

            // act & assert
            assertAll(
                { assertThat(productLikeService.countByProductId(10L)).isEqualTo(2L) },
                { assertThat(productLikeService.countByProductId(20L)).isEqualTo(1L) },
                { assertThat(productLikeService.countByProductId(30L)).isEqualTo(0L) },
            )
        }
    }
}
