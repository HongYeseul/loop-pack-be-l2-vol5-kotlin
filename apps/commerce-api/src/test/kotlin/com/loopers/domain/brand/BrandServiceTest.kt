package com.loopers.domain.brand

import com.loopers.domain.support.PageCriteria
import com.loopers.domain.support.PageResult
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

/**
 * 브랜드 조회의 두 갈래 (P-04 · P-33 · DS-3).
 *
 * 같은 `deletedAt` 을 고객과 관리자가 다르게 읽는다(기획 2-3절). 그래서 조회 메서드가
 * **이름으로 어느 질문인지 드러낸다** — `findAlive` 는 고객의 질문, `findIncludingDeleted` 는 관리자의 질문.
 * `findById` 를 두지 않아서, 부르는 쪽이 어느 질문인지 고르지 않을 수가 없다.
 */
class BrandServiceTest {
    private class FakeBrandRepository : BrandRepository {
        private val stored = linkedMapOf<Long, Brand>()
        private var sequence = 0L

        override fun save(brand: Brand): Brand {
            val id = stored.entries.find { it.value === brand }?.key ?: ++sequence
            stored[id] = brand
            return brand
        }

        override fun findAlive(brandId: Long): Brand? = stored[brandId]?.takeIf { it.deletedAt == null }

        override fun findIncludingDeleted(brandId: Long): Brand? = stored[brandId]

        override fun findAllIncludingDeletedByIds(brandIds: Collection<Long>): List<Brand> = brandIds.mapNotNull { stored[it] }

        override fun findAllIncludingDeleted(criteria: PageCriteria): PageResult<Brand> {
            // 계약대로 id 내림차순 (기획 D-3). 가짜가 진짜와 다른 순서를 주면 테스트가 거짓말을 한다.
            val all = stored.entries.sortedByDescending { it.key }.map { it.value }
            return PageResult(
                items = all.drop(criteria.offset.toInt()).take(criteria.size),
                page = criteria.page,
                size = criteria.size,
                totalCount = all.size.toLong(),
            )
        }

        /** 테스트가 id 를 알고 시작할 수 있게 한다. */
        fun seed(brand: Brand): Long = (++sequence).also { stored[it] = brand }
    }

    private val brandRepository = FakeBrandRepository()
    private val brandService = BrandService(brandRepository)

    @DisplayName("살아 있는 브랜드를 찾을 때,")
    @Nested
    inner class GetAlive {
        @DisplayName("삭제되지 않았으면, 그 브랜드를 돌려준다.")
        @Test
        fun returnsBrand_whenAlive() {
            // arrange
            val id = brandRepository.seed(Brand(name = "루퍼스"))

            // act & assert
            assertThat(brandService.getAliveOrThrow(id).name).isEqualTo("루퍼스")
        }

        @DisplayName("삭제되었으면, BRAND_NOT_FOUND 로 거절한다. 고객에게는 없는 것과 같다 (P-04).")
        @Test
        fun throwsBrandNotFound_whenDeleted() {
            // arrange
            val brand = Brand(name = "루퍼스").apply { delete() }
            val id = brandRepository.seed(brand)

            // act
            val exception = assertThrows<CoreException> { brandService.getAliveOrThrow(id) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        }

        @DisplayName("아예 없으면, 삭제된 것과 같은 오류로 답한다. 둘을 구분해 주지 않는다.")
        @Test
        fun throwsBrandNotFound_whenAbsent() {
            val exception = assertThrows<CoreException> { brandService.getAliveOrThrow(999L) }

            assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND)
        }
    }

    @DisplayName("관리자가 브랜드를 찾을 때,")
    @Nested
    inner class GetIncludingDeleted {
        @DisplayName("삭제된 것도 보인다 (P-33). 왜 안 지워지는지 판단하려면 봐야 한다.")
        @Test
        fun returnsBrand_evenWhenDeleted() {
            // arrange
            val brand = Brand(name = "루퍼스").apply { delete() }
            val id = brandRepository.seed(brand)

            // act
            val found = brandService.getIncludingDeletedOrThrow(id)

            // assert
            assertAll(
                { assertThat(found.name).isEqualTo("루퍼스") },
                { assertThat(found.deletedAt).isNotNull() },
            )
        }

        @DisplayName("아예 없으면, BRAND_NOT_FOUND 로 거절한다.")
        @Test
        fun throwsBrandNotFound_whenAbsent() {
            assertThrows<CoreException> { brandService.getIncludingDeletedOrThrow(999L) }
        }
    }

    @DisplayName("브랜드 이름을 고칠 때,")
    @Nested
    inner class ChangeName {
        @DisplayName("살아 있는 브랜드만 대상이다.")
        @Test
        fun changesName_whenAlive() {
            // arrange
            val id = brandRepository.seed(Brand(name = "루퍼스"))

            // act
            val changed = brandService.changeName(id, "루퍼스 랩")

            // assert
            assertThat(changed.name).isEqualTo("루퍼스 랩")
        }

        @DisplayName("삭제된 브랜드는 고칠 수 없다 (P-12). 대상을 findAlive 로 찾으므로 손에 들어오지 않는다.")
        @Test
        fun throwsBrandNotFound_whenDeleted() {
            // arrange
            val brand = Brand(name = "루퍼스").apply { delete() }
            val id = brandRepository.seed(brand)

            // act
            val exception = assertThrows<CoreException> { brandService.changeName(id, "루퍼스 랩") }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.BRAND_NOT_FOUND) },
                { assertThat(brand.name).isEqualTo("루퍼스") },
            )
        }
    }

    @DisplayName("브랜드를 지울 때,")
    @Nested
    inner class Delete {
        @DisplayName("삭제 시각을 남긴다 (D-2 · 논리 삭제).")
        @Test
        fun marksDeleted() {
            // arrange
            val brand = Brand(name = "루퍼스")
            val id = brandRepository.seed(brand)

            // act
            brandService.delete(id)

            // assert
            assertThat(brand.deletedAt).isNotNull()
        }

        @DisplayName("이미 삭제된 브랜드를 또 지워도 결과가 같다. BaseEntity.delete 가 멱등하고, P-12 의 금지 목록에 삭제는 없다.")
        @Test
        fun isIdempotent() {
            // arrange
            val brand = Brand(name = "루퍼스")
            val id = brandRepository.seed(brand)
            brandService.delete(id)
            val firstDeletedAt = brand.deletedAt

            // act
            brandService.delete(id)

            // assert
            assertThat(brand.deletedAt).isEqualTo(firstDeletedAt)
        }

        @DisplayName("아예 없으면, BRAND_NOT_FOUND 로 거절한다.")
        @Test
        fun throwsBrandNotFound_whenAbsent() {
            assertThrows<CoreException> { brandService.delete(999L) }
        }
    }

    @DisplayName("브랜드 목록을 볼 때,")
    @Nested
    inner class GetAll {
        @DisplayName("관리자에게는 삭제된 것도 함께 나오고, 최근에 만든 것이 앞에 온다 (P-33 · D-3).")
        @Test
        fun includesDeletedInIdDescendingOrder() {
            // arrange
            brandRepository.seed(Brand(name = "먼저 만든 브랜드"))
            brandRepository.seed(Brand(name = "지워진 브랜드").apply { delete() })

            // act
            val result = brandService.getAllIncludingDeleted(PageCriteria(page = 0, size = 20))

            // assert
            assertAll(
                { assertThat(result.totalCount).isEqualTo(2L) },
                { assertThat(result.items.map { it.name }).containsExactly("지워진 브랜드", "먼저 만든 브랜드") },
            )
        }
    }
}
