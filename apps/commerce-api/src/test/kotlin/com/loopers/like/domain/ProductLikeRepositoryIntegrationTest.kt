package com.loopers.like.domain

import com.loopers.like.infrastructure.ProductLikeJpaRepository
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException

/**
 * UNIQUE(user_id, product_id) 제약이 실제 테이블에 걸렸는지 확인 (가짜 저장소는 코틀린으로 흉내)
 * FK 가 없는 참조라 행 없이 숫자를 쓰고, 컬럼 뒤바뀜이 드러나도록 서로 다른 값(1, 10) 사용
 */
@SpringBootTest
class ProductLikeRepositoryIntegrationTest @Autowired constructor(
    private val productLikeRepository: ProductLikeRepository,
    private val productLikeJpaRepository: ProductLikeJpaRepository,
    private val entityManager: EntityManager,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val USER_ID = 1L
        private const val PRODUCT_ID = 10L
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    private fun countRows(): Long =
        (entityManager.createNativeQuery("SELECT COUNT(*) FROM product_like").singleResult as Number).toLong()

    @DisplayName("같은 사용자–상품 조합은,")
    @Nested
    inner class UniquePair {
        @DisplayName("두 행이 될 수 없다.")
        @Test
        fun isRejectedByConstraint() {
            // arrange
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))

            // act & assert
            assertThrows<DataIntegrityViolationException> {
                productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))
            }
        }

        @DisplayName("사용자가 다르면 막지 않는다 — 좋아요 수가 세어지려면 여러 사람이 같은 상품을 좋아할 수 있어야 한다.")
        @Test
        fun allowsDifferentUsersOnSameProduct() {
            // act
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID + 1, productId = PRODUCT_ID))

            // assert
            assertThat(productLikeRepository.countByProductId(PRODUCT_ID)).isEqualTo(2L)
        }
    }

    @DisplayName("관계를 저장하면,")
    @Nested
    inner class Saved {
        /** 둘 다 Long 이라 뒤집어 넣어도 컴파일되므로 컬럼별 값 확인 */
        @DisplayName("사용자와 상품이 각자의 컬럼에 들어간다.")
        @Test
        fun storesEachIdInItsOwnColumn() {
            // arrange
            val saved = productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))

            // act
            val row = entityManager
                .createNativeQuery("SELECT user_id, product_id FROM product_like WHERE id = :id")
                .setParameter("id", saved.id)
                .singleResult as Array<*>

            // assert
            assertAll(
                { assertThat((row[0] as Number).toLong()).isEqualTo(USER_ID) },
                { assertThat((row[1] as Number).toLong()).isEqualTo(PRODUCT_ID) },
            )
        }
    }

    @DisplayName("관계를 취소하면,")
    @Nested
    inner class Deleted {
        @DisplayName("행이 물리적으로 사라진다.")
        @Test
        fun removesRowFromTable() {
            // arrange
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))

            // act
            productLikeRepository.delete(userId = USER_ID, productId = PRODUCT_ID)

            // assert
            assertAll(
                { assertThat(countRows()).isEqualTo(0L) },
                { assertThat(productLikeRepository.exists(userId = USER_ID, productId = PRODUCT_ID)).isFalse() },
            )
        }

        @DisplayName("없는 관계를 취소해도 성공이다.")
        @Test
        fun acceptsMissingRelation() {
            // act & assert
            assertDoesNotThrow { productLikeRepository.delete(userId = USER_ID, productId = PRODUCT_ID) }
            assertThat(countRows()).isEqualTo(0L)
        }

        @DisplayName("같은 상품에 걸린 남의 관계는 남는다.")
        @Test
        fun leavesOtherUsersRelation() {
            // arrange
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID + 1, productId = PRODUCT_ID))

            // act
            productLikeRepository.delete(userId = USER_ID, productId = PRODUCT_ID)

            // assert
            assertThat(productLikeRepository.countByProductId(PRODUCT_ID)).isEqualTo(1L)
        }
    }

    @DisplayName("한 페이지의 좋아요 수를 셀 때,")
    @Nested
    inner class CountByProductIds {
        @DisplayName("상품마다 세어 한 번에 돌려준다.")
        @Test
        fun countsEachProduct() {
            // arrange
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID + 1, productId = PRODUCT_ID))
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID + 1))

            // act
            val counts = productLikeRepository.countByProductIds(listOf(PRODUCT_ID, PRODUCT_ID + 1, PRODUCT_ID + 2))

            // assert
            assertAll(
                { assertThat(counts[PRODUCT_ID]).isEqualTo(2L) },
                { assertThat(counts[PRODUCT_ID + 1]).isEqualTo(1L) },
                { assertThat(counts).doesNotContainKey(PRODUCT_ID + 2) },
            )
        }

        /** 빈 목록의 IN () 은 SQL 오류, 조회 자체를 생략 */
        @DisplayName("빈 목록을 주면 조회하지 않는다.")
        @Test
        fun acceptsEmptyInput() {
            assertThat(productLikeRepository.countByProductIds(emptyList())).isEmpty()
        }
    }
}
