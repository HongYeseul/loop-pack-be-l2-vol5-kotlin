package com.loopers.domain.brand

import com.loopers.domain.support.PageCriteria
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 논리 삭제가 **DB 까지 내려가서** 동작하는지 본다 (D-2 · DS-3).
 *
 * 단위 테스트의 가짜 저장소는 `deletedAt == null` 을 코틀린으로 판단한다.
 * 진짜로 확인해야 하는 것은 그 조건이 **SQL 에 들어가는지** 다. 여기가 그 자리다.
 */
@SpringBootTest
class BrandRepositoryIntegrationTest @Autowired constructor(
    private val brandRepository: BrandRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("논리 삭제된 브랜드는,")
    @Nested
    inner class SoftDeleted {
        @DisplayName("행이 남아 있지만 findAlive 에는 안 나온다 (P-12).")
        @Test
        fun isHiddenFromFindAlive() {
            // arrange
            val brand = brandJpaRepository.save(Brand(name = "루퍼스"))
            brand.delete()
            brandJpaRepository.saveAndFlush(brand)

            // act
            val alive = brandRepository.findAlive(brand.brandId)
            val includingDeleted = brandRepository.findIncludingDeleted(brand.brandId)

            // assert
            assertAll(
                { assertThat(alive).isNull() },
                { assertThat(includingDeleted).isNotNull() },
                { assertThat(includingDeleted?.name).isEqualTo("루퍼스") },
                { assertThat(includingDeleted?.deletedAt).isNotNull() },
            )
        }

        @DisplayName("관리자 목록에는 그대로 나온다 (P-33).")
        @Test
        fun staysInAdminList() {
            // arrange
            brandJpaRepository.save(Brand(name = "살아있는 브랜드"))
            val deleted = brandJpaRepository.save(Brand(name = "지워진 브랜드"))
            deleted.delete()
            brandJpaRepository.saveAndFlush(deleted)

            // act
            val result = brandRepository.findAllIncludingDeleted(PageCriteria(page = 0, size = 20))

            // assert
            assertThat(result.items.map { it.name }).contains("지워진 브랜드")
        }
    }

    @DisplayName("목록을 페이지로 끊어 볼 때,")
    @Nested
    inner class Paging {
        @DisplayName("id 내림차순이라 페이지끼리 겹치지도 빠지지도 않는다 (D-3).")
        @Test
        fun doesNotOverlapOrSkipAcrossPages() {
            // arrange · 이름이 모두 같아도 id 가 유일하므로 동점이 없다
            repeat(4) { brandJpaRepository.save(Brand(name = "같은 이름")) }
            val allIds = brandJpaRepository.findAll().map { it.id }.sortedDescending()

            // act
            val first = brandRepository.findAllIncludingDeleted(PageCriteria(page = 0, size = 2))
            val second = brandRepository.findAllIncludingDeleted(PageCriteria(page = 1, size = 2))

            // assert
            assertAll(
                { assertThat(first.items.map { it.id }).containsExactly(allIds[0], allIds[1]) },
                { assertThat(second.items.map { it.id }).containsExactly(allIds[2], allIds[3]) },
                { assertThat(first.items.map { it.id }).doesNotContainAnyElementsOf(second.items.map { it.id }) },
                { assertThat(first.totalCount).isEqualTo(4L) },
            )
        }
    }
}
