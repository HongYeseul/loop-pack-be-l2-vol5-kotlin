package com.loopers.domain.product

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 정렬 값이 무엇을 받아들이는지 한 곳에서 정한다 (P-08).
 *
 * `domain` 에 두는 이유: 받아들이는 값의 목록은 계약이고, `interfaces` 와 저장소가 **같은 목록**을
 * 봐야 한다. 컨트롤러에 문자열로 적으면 저장소가 모르는 값이 통과할 수 있고,
 * 저장소에만 적으면 잘못된 값이 DB 까지 내려간 뒤에 터진다.
 *
 * `interfaces` 가 이 타입을 쓰는 것은 ArchUnit 4번 규칙이 허용하는 **값** 의존이다 (설계 1-3절).
 */
class ProductSortTest {
    @DisplayName("정렬 값을 해석할 때,")
    @Nested
    inner class From {
        @DisplayName("규격 안의 값은 그대로 해석한다 (P-08).")
        @Test
        fun parsesSupportedValues() {
            assertThat(ProductSort.from("latest")).isEqualTo(ProductSort.LATEST)
            assertThat(ProductSort.from("price_asc")).isEqualTo(ProductSort.PRICE_ASC)
        }

        @DisplayName("값이 없으면 최신순이다 (설계 6-2절 C-2 · 기본 latest).")
        @Test
        fun defaultsToLatest() {
            assertThat(ProductSort.from(null)).isEqualTo(ProductSort.LATEST)
        }

        @DisplayName("규격 밖의 값은 INVALID_SORT 로 거절한다. 조용히 기본값으로 되돌리지 않는다 (P-08).")
        @ParameterizedTest
        @ValueSource(strings = ["LATEST", "price", "price_desc", "", " "])
        fun rejectsUnsupportedValue(raw: String) {
            // act
            val exception = assertThrows<CoreException> { ProductSort.from(raw) }

            // assert · 기본값으로 되돌리면 요청자는 자기가 보낸 정렬이 무시된 것을 끝내 모른다
            assertThat(exception.errorType).isEqualTo(ErrorType.INVALID_SORT)
        }

        @DisplayName("likes_desc 는 아직 없다. 좋아요 관계가 생기는 4단계에서 붙인다 (설계 10절).")
        @Test
        fun doesNotSupportLikesDescYet() {
            assertThrows<CoreException> { ProductSort.from("likes_desc") }
        }
    }
}
