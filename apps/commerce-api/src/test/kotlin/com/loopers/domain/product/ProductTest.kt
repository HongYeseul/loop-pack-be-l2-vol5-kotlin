package com.loopers.domain.product

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 이번 주의 **대표 TDD 규칙 — 재고 차감** (기획 9절 · 설계 9절).
 *
 * 기대값은 설계 6-4절 표를 그대로 옮겼다. 재고 5 에서 6 차감은 거절되고 재고는 5 로 남으며,
 * 재고 5 에서 2 차감하면 3 이 남는다.
 *
 * **판단과 변경이 같은 객체에 있다** (설계 3절). "뺄 수 있나"를 밖에서 묻고 밖에서 빼면
 * 묻지 않고 빼는 경로가 언제든 생긴다. 그러면 P-27("거절되면 재고가 그대로")이 호출자마다 달라진다.
 *
 * 거절은 **식별자까지** 확인한다 (DS-8). 재고 부족과 잘못된 수량은 둘 다 거절이지만
 * 요청자가 다음에 할 일이 다르다 — 수량을 줄이거나 포기한다 / 수량을 고친다.
 * 예외가 났다는 것만 보면 그 차이가 테스트에 남지 않는다.
 */
class ProductTest {
    @DisplayName("재고를 뺄 때,")
    @Nested
    inner class DecreaseStock {
        @DisplayName("남은 것보다 많이 빼려 하면, 거절하고 재고를 그대로 둔다 (재고 5 에서 6 차감).")
        @Test
        fun rejectsAndKeepsStock_whenQuantityExceedsStock() {
            // arrange
            val product = Product(brandId = 1L, name = "루퍼스 티셔츠", price = 3_500L, stock = 5)

            // act
            val exception = assertThrows<CoreException> { product.decreaseStock(6) }

            // assert · 거절은 "아무것도 하지 않음" 이어야 한다 (P-27)
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.OUT_OF_STOCK) },
                { assertThat(product.stock).isEqualTo(5) },
            )
        }

        @DisplayName("남은 것 안에서 빼면, 뺀 만큼 줄어든다 (재고 5 에서 2 차감 후 3).")
        @Test
        fun decreasesByQuantity_whenWithinStock() {
            // arrange
            val product = Product(brandId = 1L, name = "루퍼스 티셔츠", price = 3_500L, stock = 5)

            // act
            product.decreaseStock(2)

            // assert
            assertThat(product.stock).isEqualTo(3)
        }

        @DisplayName("남은 것을 정확히 다 빼면, 0 이 된다. 0 은 부족이 아니다 (P-37 · 재고없음은 파생값).")
        @Test
        fun allowsExactStock() {
            // arrange
            val product = Product(brandId = 1L, name = "루퍼스 티셔츠", price = 3_500L, stock = 5)

            // act
            product.decreaseStock(5)

            // assert
            assertThat(product.stock).isZero()
        }

        @DisplayName("0 개나 음수를 빼려 하면, 거절하고 재고를 그대로 둔다 (P-24).")
        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun rejectsNonPositiveQuantity(quantity: Int) {
            // arrange
            val product = Product(brandId = 1L, name = "루퍼스 티셔츠", price = 3_500L, stock = 5)

            // act
            val exception = assertThrows<CoreException> { product.decreaseStock(quantity) }

            // assert · 재고 부족과 **다른** 식별자다 (DS-8)
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.INVALID_QUANTITY) },
                { assertThat(product.stock).isEqualTo(5) },
            )
        }
    }

    @DisplayName("상품을 만들 때,")
    @Nested
    inner class Create {
        @DisplayName("판매중으로 시작하고, 삭제되지 않은 상태다 (P-36 · 기본값).")
        @Test
        fun startsOnSaleAndAlive() {
            // act
            val product = Product(brandId = 1L, name = "루퍼스 티셔츠", price = 3_500L, stock = 5)

            // assert
            assertAll(
                { assertThat(product.status).isEqualTo(ProductStatus.ON_SALE) },
                { assertThat(product.deletedAt).isNull() },
                { assertThat(product.brandId).isEqualTo(1L) },
            )
        }

        @DisplayName("이름이 1~100자면 받아들인다 (P-06).")
        @ParameterizedTest
        @ValueSource(ints = [1, 100])
        fun acceptsNameWithinRange(length: Int) {
            assertThat(product(name = "가".repeat(length)).name).hasSize(length)
        }

        @DisplayName("이름이 비었거나 100자를 넘으면, 만들어지지 않는다 (P-06).")
        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\t"])
        fun rejectsBlankName(name: String) {
            assertThat(assertThrows<CoreException> { product(name = name) }.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        }

        @DisplayName("가격이 0 이상 1억 이하면 받아들인다. **0원을 허용한다** (P-06 · D-4).")
        @ParameterizedTest
        @ValueSource(longs = [0L, 1L, 100_000_000L])
        fun acceptsPriceWithinRange(price: Long) {
            assertThat(product(price = price).price).isEqualTo(price)
        }

        @DisplayName("가격이 음수거나 1억을 넘으면, 만들어지지 않는다 (P-06).")
        @ParameterizedTest
        @ValueSource(longs = [-1L, 100_000_001L])
        fun rejectsPriceOutOfRange(price: Long) {
            assertThrows<CoreException> { product(price = price) }
        }

        @DisplayName("재고가 음수면, 만들어지지 않는다 (P-07).")
        @Test
        fun rejectsNegativeStock() {
            assertThrows<CoreException> { product(stock = -1) }
        }
    }

    @DisplayName("이름과 가격을 고칠 때,")
    @Nested
    inner class ChangeNameAndPrice {
        @DisplayName("둘을 함께 바꾼다. 브랜드는 바꾸지 않는다 (A-9 · P-05).")
        @Test
        fun changesNameAndPriceOnly() {
            // arrange
            val product = product()

            // act
            product.changeNameAndPrice(name = "루퍼스 후드", price = 12_000L)

            // assert
            assertAll(
                { assertThat(product.name).isEqualTo("루퍼스 후드") },
                { assertThat(product.price).isEqualTo(12_000L) },
                { assertThat(product.brandId).isEqualTo(1L) },
            )
        }

        @DisplayName("하나라도 범위를 벗어나면, 둘 다 바뀌지 않는다. 반쯤 바뀐 상품은 없다.")
        @Test
        fun changesNothing_whenEitherIsInvalid() {
            // arrange
            val product = product(name = "루퍼스 티셔츠", price = 3_500L)

            // act
            assertThrows<CoreException> { product.changeNameAndPrice(name = "루퍼스 후드", price = -1L) }

            // assert
            assertAll(
                { assertThat(product.name).isEqualTo("루퍼스 티셔츠") },
                { assertThat(product.price).isEqualTo(3_500L) },
            )
        }
    }

    @DisplayName("재고를 설정할 때,")
    @Nested
    inner class ChangeStock {
        @DisplayName("증감이 아니라 최종 수량이다. 같은 요청을 두 번 보내도 결과가 같다 (P-07).")
        @Test
        fun setsFinalQuantity() {
            // arrange
            val product = product(stock = 5)

            // act
            product.changeStock(3)
            product.changeStock(3)

            // assert · 5 - 3 - 3 = -1 이 아니다
            assertThat(product.stock).isEqualTo(3)
        }

        @DisplayName("0 으로도 설정할 수 있다. 재고 0 은 정상 상태다 (P-37 · 재고없음은 파생값).")
        @Test
        fun allowsZero() {
            // arrange
            val product = product(stock = 5)

            // act
            product.changeStock(0)

            // assert
            assertThat(product.stock).isZero()
        }

        @DisplayName("음수로는 설정할 수 없다. 재고를 그대로 둔다 (P-07).")
        @Test
        fun rejectsNegative() {
            // arrange
            val product = product(stock = 5)

            // act
            assertThrows<CoreException> { product.changeStock(-1) }

            // assert
            assertThat(product.stock).isEqualTo(5)
        }
    }

    @DisplayName("판매 상태를 바꿀 때,")
    @Nested
    inner class ChangeStatus {
        @DisplayName("판매중지했다가 다시 판매중으로 되돌릴 수 있다 (P-36).")
        @Test
        fun suspendsAndResumes() {
            // arrange
            val product = product()

            // act
            product.changeStatus(ProductStatus.SUSPENDED)
            val suspended = product.status
            product.changeStatus(ProductStatus.ON_SALE)

            // assert
            assertAll(
                { assertThat(suspended).isEqualTo(ProductStatus.SUSPENDED) },
                { assertThat(product.status).isEqualTo(ProductStatus.ON_SALE) },
            )
        }

        @DisplayName("단종시키면 되돌릴 수 없다. 상태는 단종으로 남는다 (P-36 · 최종).")
        @Test
        fun rejectsAnyWayBackFromDiscontinued() {
            // arrange
            val product = product().apply { changeStatus(ProductStatus.DISCONTINUED) }

            // act
            val exception = assertThrows<CoreException> { product.changeStatus(ProductStatus.ON_SALE) }

            // assert
            assertAll(
                { assertThat(exception.errorType).isEqualTo(ErrorType.BAD_REQUEST) },
                { assertThat(product.status).isEqualTo(ProductStatus.DISCONTINUED) },
            )
        }

        @DisplayName("판매 상태를 바꿔도 재고는 건드리지 않는다. 둘은 다른 축이다 (DS-11).")
        @Test
        fun leavesStockAlone() {
            // arrange
            val product = product(stock = 5)

            // act
            product.changeStatus(ProductStatus.SUSPENDED)

            // assert
            assertThat(product.stock).isEqualTo(5)
        }
    }

    /**
     * **"지금 살 수 있나" 는 상태와 재고를 모두 본다** (P-37 · P-38 · D-10).
     *
     * 고객 응답에 나가는 "구매 가능 여부" 가 이것이다. 재고 수량은 고객에게 보이지 않으므로
     * (D-10), 이 하나가 고객이 보는 구매 가능성의 전부다.
     */
    @DisplayName("지금 살 수 있는지 물을 때,")
    @Nested
    inner class Purchasable {
        @DisplayName("판매중이고 재고가 있으면, 살 수 있다.")
        @Test
        fun trueWhenOnSaleAndInStock() {
            assertThat(product(stock = 1).purchasable).isTrue()
        }

        @DisplayName("판매중이어도 재고가 0 이면, 살 수 없다 (P-37 · 재고없음).")
        @Test
        fun falseWhenOutOfStock() {
            assertThat(product(stock = 0).purchasable).isFalse()
        }

        @DisplayName("재고가 있어도 판매중지·단종이면, 살 수 없다 (P-38).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["SUSPENDED", "DISCONTINUED"])
        fun falseWhenNotOnSale(status: ProductStatus) {
            assertThat(product(stock = 5).apply { changeStatus(status) }.purchasable).isFalse()
        }
    }

    private fun product(
        name: String = "루퍼스 티셔츠",
        price: Long = 3_500L,
        stock: Int = 5,
    ): Product = Product(brandId = 1L, name = name, price = price, stock = stock)
}
