package com.loopers.domain.like

import com.loopers.infrastructure.like.ProductLikeJpaRepository
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
 * 좋아요의 **저장 제약이 DB 까지 내려가는지** 본다 (설계 9절 · 7-1절).
 *
 * 단위 테스트의 가짜 저장소는 `UNIQUE(user_id, product_id)` 를 코틀린으로 흉내낸다.
 * 그 제약이 진짜로 테이블에 걸려 있는지는 여기서만 확인할 수 있다 (P-14).
 *
 * 사용자·상품 행을 만들지 않고 숫자를 그대로 쓴다. 애그리게잇을 넘는 참조라 FK 가 없고(설계 2-2절),
 * **뒤집어 넣으면 드러나는 값**(1 과 10)이어야 컬럼 확인이 뜻을 가진다 (DS-13).
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
        @DisplayName("두 행이 될 수 없다 (P-14).")
        @Test
        fun isRejectedByConstraint() {
            // arrange
            productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))

            // act & assert
            assertThrows<DataIntegrityViolationException> {
                productLikeJpaRepository.saveAndFlush(ProductLike(userId = USER_ID, productId = PRODUCT_ID))
            }
        }

        @DisplayName("사용자가 다르면 막지 않는다 — 좋아요 수가 세어지려면 여러 사람이 같은 상품을 좋아할 수 있어야 한다 (P-15).")
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
        /** 둘 다 `Long` 이라 뒤집어 넣어도 컴파일된다 (DS-13). 타입이 못 막는 자리를 여기서 본다. */
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
        @DisplayName("행이 물리적으로 사라진다 (P-17 · D-2).")
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

        @DisplayName("없는 관계를 취소해도 성공이다 (P-17).")
        @Test
        fun acceptsMissingRelation() {
            // act & assert
            assertDoesNotThrow { productLikeRepository.delete(userId = USER_ID, productId = PRODUCT_ID) }
            assertThat(countRows()).isEqualTo(0L)
        }

        @DisplayName("같은 상품에 걸린 남의 관계는 남는다 (P-02).")
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
        @DisplayName("상품마다 세어 한 번에 돌려준다 (P-15 · DS-1).")
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

        /** 빈 목록에 `IN ()` 을 내리면 SQL 이 깨진다. 상품이 없는 페이지는 조회 자체가 필요 없다. */
        @DisplayName("빈 목록을 주면 조회하지 않는다.")
        @Test
        fun acceptsEmptyInput() {
            assertThat(productLikeRepository.countByProductIds(emptyList())).isEmpty()
        }
    }
}
