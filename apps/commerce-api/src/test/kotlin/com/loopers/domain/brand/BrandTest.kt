package com.loopers.domain.brand

import com.loopers.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 브랜드는 상품을 묶는 이름이다 (기획 4절). 이번 단계에서 가지는 규칙은 이름 범위 하나뿐이다.
 *
 * **삭제된 브랜드를 고칠 수 없다(P-12)는 규칙은 여기 없다.** DS-3 이 그 강제를
 * 조회 이름(`findAlive`)에 맡기기로 정했다 — 수정 대상을 `findAlive` 로만 찾으면
 * 삭제된 것은 애초에 손에 들어오지 않는다. 같은 규칙을 엔티티에도 적으면 두 곳이 갈라진다.
 */
class BrandTest {
    @DisplayName("브랜드를 만들 때,")
    @Nested
    inner class Create {
        @DisplayName("이름을 그대로 보관하고, 삭제되지 않은 상태로 시작한다.")
        @Test
        fun keepsNameAndStartsAlive() {
            // act
            val brand = Brand(name = "루퍼스")

            // assert
            assertAll(
                { assertThat(brand.name).isEqualTo("루퍼스") },
                { assertThat(brand.deletedAt).isNull() },
            )
        }

        @DisplayName("이름이 1~100자면 받아들인다.")
        @ParameterizedTest
        @ValueSource(ints = [1, 100])
        fun acceptsNameWithinRange(length: Int) {
            // arrange
            val name = "가".repeat(length)

            // act & assert
            assertThat(Brand(name = name).name).isEqualTo(name)
        }

        @DisplayName("이름이 비었거나 공백뿐이면, 만들어지지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t"])
        fun rejectsBlankName(name: String) {
            assertThrows<CoreException> { Brand(name = name) }
        }

        @DisplayName("이름이 100자를 넘으면, 만들어지지 않는다.")
        @Test
        fun rejectsTooLongName() {
            assertThrows<CoreException> { Brand(name = "가".repeat(101)) }
        }
    }

    @DisplayName("브랜드 이름을 고칠 때,")
    @Nested
    inner class ChangeName {
        @DisplayName("새 이름으로 바꾼다.")
        @Test
        fun changesName() {
            // arrange
            val brand = Brand(name = "루퍼스")

            // act
            brand.changeName("루퍼스 랩")

            // assert
            assertThat(brand.name).isEqualTo("루퍼스 랩")
        }

        @DisplayName("생성과 같은 범위를 지킨다. 벗어나면 이름이 바뀌지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "   "])
        fun rejectsInvalidName(newName: String) {
            // arrange
            val brand = Brand(name = "루퍼스")

            // act
            assertThrows<CoreException> { brand.changeName(newName) }

            // assert
            assertThat(brand.name).isEqualTo("루퍼스")
        }
    }

    @DisplayName("브랜드를 지울 때,")
    @Nested
    inner class Delete {
        @DisplayName("행을 지우지 않고 삭제 시각만 남긴다 (D-2 · 논리 삭제).")
        @Test
        fun marksDeletedAt() {
            // arrange
            val brand = Brand(name = "루퍼스")

            // act
            brand.delete()

            // assert
            assertAll(
                { assertThat(brand.deletedAt).isNotNull() },
                { assertThat(brand.name).isEqualTo("루퍼스") },
            )
        }
    }
}
