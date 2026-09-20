package com.loopers.domain.support

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 페이지 입력의 규격 (설계 6-1절 공통).
 *
 * 목록을 가진 API 가 여럿(C-2 · C-6 · C-11 · A-1 · A-6 · A-12)이라 한 곳에 둔다.
 * 도메인마다 따로 적으면 같은 `INVALID_PAGE` 가 도메인마다 다른 범위를 뜻하게 된다.
 *
 * **범위 위반은 형식이 아니라 규칙이므로 `domain` 이다** (DS-2).
 * `page=abc` 처럼 숫자가 아닌 것은 `interfaces` 가 `BAD_REQUEST` 로 거른다.
 */
class PageCriteriaTest {
    @DisplayName("규격 안의 값이면,")
    @Nested
    inner class Valid {
        @DisplayName("그대로 받아들이고, 건너뛸 행 수를 계산한다.")
        @ParameterizedTest
        @CsvSource("0, 20, 0", "1, 20, 20", "2, 2, 4", "3, 100, 300")
        fun keepsValuesAndComputesOffset(page: Int, size: Int, expectedOffset: Long) {
            // act
            val criteria = PageCriteria(page = page, size = size)

            // assert
            assertAll(
                { assertThat(criteria.page).isEqualTo(page) },
                { assertThat(criteria.size).isEqualTo(size) },
                { assertThat(criteria.offset).isEqualTo(expectedOffset) },
            )
        }

        @DisplayName("입력이 없으면, 기본값으로 첫 페이지를 본다.")
        @Test
        fun fallsBackToDefault() {
            // act
            val criteria = PageCriteria.of(page = null, size = null)

            // assert
            assertAll(
                { assertThat(criteria.page).isEqualTo(0) },
                { assertThat(criteria.size).isEqualTo(PageCriteria.DEFAULT_SIZE) },
            )
        }
    }

    @DisplayName("규격을 벗어나면,")
    @Nested
    inner class Invalid {
        @DisplayName("INVALID_PAGE 로 거절한다.")
        @ParameterizedTest
        @CsvSource("-1, 20", "0, 0", "0, -1", "0, 101")
        fun rejectsWithInvalidPage(page: Int, size: Int) {
            // act
            val exception = assertThrows<CoreException> { PageCriteria(page = page, size = size) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.INVALID_PAGE)
        }
    }
}
