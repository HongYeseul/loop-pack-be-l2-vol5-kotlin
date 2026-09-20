package com.loopers.domain.order

import com.loopers.fixture.OrderFixture
import com.loopers.fixture.OrderFixture.BASE_TIME
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 주문의 상태와 금액 규칙 (설계 9절 · domain 단위).
 *
 * 여기서 확인하는 것은 **`Order` 혼자 답할 수 있는 것**뿐이다 — 합계 계산(P-28), 중복 품목(P-25),
 * 상태 전이(P-26 · P-29), 만료 경계(P-32). 재고와 잔액은 다른 애그리게잇이라
 * 그 협력은 `OrderFacade` 통합 테스트가 본다 (DS-7).
 *
 * `now` 를 값으로 준다 (설계 3절). 그래서 "10분 1초 뒤" 가 기다림이 아니라 덧셈이다.
 */
class OrderTest {
    @DisplayName("주문을 만들 때,")
    @Nested
    inner class Create {
        @DisplayName("품목의 수량 × 단가를 더해 합계를 만든다 (P-28).")
        @Test
        fun sumsItemAmounts() {
            // act
            val order = OrderFixture.order(
                userId = 1L,
                items = listOf(
                    OrderFixture.item(productId = 1L, quantity = 2, unitPrice = 3_500L),
                    OrderFixture.item(productId = 2L, quantity = 1, unitPrice = 1_000L),
                ),
            )

            // assert
            assertThat(order.totalAmount).isEqualTo(8_000L)
        }

        @DisplayName("합계를 밖에서 받지 않는다. 만들어진 주문은 DRAFT 이고 아직 결제액이 없다 (P-23).")
        @Test
        fun startsAsDraftWithoutPaidAmount() {
            // act
            val order = OrderFixture.order(userId = 1L)

            // assert
            assertAll(
                { assertThat(order.status).isEqualTo(OrderStatus.DRAFT) },
                { assertThat(order.paidAmount).isNull() },
                { assertThat(order.confirmedAt).isNull() },
                { assertThat(order.canceledAt).isNull() },
            )
        }

        /** 설계 6-4절 기대값 — `[{1, 2}, {1, 3}]` */
        @DisplayName("같은 상품이 두 품목으로 들어오면, 거절한다 (P-25 · DS-2).")
        @Test
        fun rejectsDuplicateProduct() {
            // act
            val exception = assertThrows<CoreException> {
                OrderFixture.order(
                    userId = 1L,
                    items = listOf(
                        OrderFixture.item(productId = 1L, quantity = 2),
                        OrderFixture.item(productId = 1L, quantity = 3),
                    ),
                )
            }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.DUPLICATE_ORDER_ITEM)
        }

        @DisplayName("품목이 하나도 없으면, 거절한다. 살 것이 없는 주문은 0원 확정(P-30)과 구분되지 않는다.")
        @Test
        fun rejectsEmptyItems() {
            // act
            val exception = assertThrows<CoreException> { OrderFixture.order(userId = 1L, items = emptyList()) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        }

        @DisplayName("수량이 0 이하면, 거절한다 (P-24).")
        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun rejectsNonPositiveQuantity(quantity: Int) {
            // act
            val exception = assertThrows<CoreException> { OrderFixture.item(productId = 1L, quantity = quantity) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.INVALID_QUANTITY)
        }

        @DisplayName("단가 0원인 품목도 담는다 (D-4 · P-30). 서비스로 끼워 주는 상품이 있다.")
        @Test
        fun allowsZeroUnitPrice() {
            // act
            val order = OrderFixture.order(userId = 1L, items = listOf(OrderFixture.item(productId = 1L, unitPrice = 0L)))

            // assert
            assertThat(order.totalAmount).isEqualTo(0L)
        }

        @DisplayName("만료 시각을 생성 10분 뒤로 **저장한다** (P-32 · DS-4).")
        @Test
        fun storesExpiresAtTenMinutesLater() {
            // act
            val order = OrderFixture.order(userId = 1L, now = BASE_TIME)

            // assert · 계산하지 않고 저장하므로, 정책이 바뀌어도 이 주문의 만료 시각은 그대로다
            assertThat(order.expiresAt).isEqualTo(BASE_TIME.plusMinutes(10))
        }

        @DisplayName("확정할 때 필요한 상품과 수량을 내준다 (설계 5절 ⑥ ⑦).")
        @Test
        fun tellsProductIdsAndQuantities() {
            // arrange
            val order = OrderFixture.order(
                userId = 1L,
                items = listOf(
                    OrderFixture.item(productId = 7L, quantity = 2),
                    OrderFixture.item(productId = 9L, quantity = 5),
                ),
            )

            // assert
            assertAll(
                { assertThat(order.productIds()).containsExactly(7L, 9L) },
                { assertThat(order.quantityOf(9L)).isEqualTo(5) },
            )
        }
    }

    @DisplayName("주문을 확정할 때,")
    @Nested
    inner class Confirm {
        @DisplayName("합계를 결제액으로 옮겨 적고, 확정 시각을 남긴다 (P-26 · P-28).")
        @Test
        fun recordsPaidAmountAndConfirmedAt() {
            // arrange
            val order = OrderFixture.order(userId = 1L)

            // act
            order.confirm(BASE_TIME.plusMinutes(1))

            // assert · 결제액을 밖에서 받지 않는다. 받으면 합계와 다른 값이 들어올 수 있다 (P-28)
            assertAll(
                { assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED) },
                { assertThat(order.paidAmount).isEqualTo(order.totalAmount) },
                { assertThat(order.confirmedAt).isEqualTo(BASE_TIME.plusMinutes(1)) },
            )
        }

        @DisplayName("합계가 0원이어도 확정된다 (P-30 · D-11).")
        @Test
        fun allowsZeroTotal() {
            // arrange
            val order = OrderFixture.order(userId = 1L, items = listOf(OrderFixture.item(productId = 1L, unitPrice = 0L)))

            // act
            order.confirm(BASE_TIME)

            // assert · 결제액 0 과 "아직 결제 안 함"(null) 은 다르다
            assertAll(
                { assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED) },
                { assertThat(order.paidAmount).isEqualTo(0L) },
            )
        }

        @DisplayName("이미 확정된 주문은 다시 확정할 수 없다 (P-29).")
        @Test
        fun rejectsAlreadyConfirmed() {
            // arrange
            val order = OrderFixture.order(userId = 1L).apply { confirm(BASE_TIME) }

            // act
            val exception = assertThrows<CoreException> { order.confirm(BASE_TIME) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_NOT_DRAFT)
        }

        @DisplayName("취소된 주문도, 만료된 주문도 확정할 수 없다 (P-29 · P-32).")
        @Test
        fun rejectsCanceledAndExpired() {
            // arrange
            val canceled = OrderFixture.order(userId = 1L).apply { cancel(BASE_TIME) }
            val expired = OrderFixture.order(userId = 1L).apply { expire() }

            // assert
            assertAll(
                { assertThat(assertThrows<CoreException> { canceled.confirm(BASE_TIME) }.errorType).isEqualTo(ErrorType.ORDER_NOT_DRAFT) },
                { assertThat(assertThrows<CoreException> { expired.confirm(BASE_TIME) }.errorType).isEqualTo(ErrorType.ORDER_NOT_DRAFT) },
            )
        }
    }

    @DisplayName("주문을 취소할 때,")
    @Nested
    inner class Cancel {
        @DisplayName("상태만 바꾸고 취소 시각을 남긴다. 차감한 것이 없어 되돌릴 것도 없다 (P-29 · D-7).")
        @Test
        fun marksCanceled() {
            // arrange
            val order = OrderFixture.order(userId = 1L)

            // act
            order.cancel(BASE_TIME.plusMinutes(1))

            // assert
            assertAll(
                { assertThat(order.status).isEqualTo(OrderStatus.CANCELED) },
                { assertThat(order.canceledAt).isEqualTo(BASE_TIME.plusMinutes(1)) },
                { assertThat(order.paidAmount).isNull() },
            )
        }

        @DisplayName("확정된 주문은 취소 대상이 아니다 (P-29 · D-7).")
        @Test
        fun rejectsConfirmed() {
            // arrange
            val order = OrderFixture.order(userId = 1L).apply { confirm(BASE_TIME) }

            // act
            val exception = assertThrows<CoreException> { order.cancel(BASE_TIME) }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_NOT_DRAFT)
        }
    }

    @DisplayName("만료를 볼 때,")
    @Nested
    inner class Expiry {
        @DisplayName("만료 시각 정각은 아직 만료가 아니다 (P-32 · 경계).")
        @Test
        fun isNotExpiredAtExactly() {
            // arrange
            val order = OrderFixture.order(userId = 1L, now = BASE_TIME)

            // assert
            assertThat(order.isExpired(BASE_TIME.plusMinutes(10))).isFalse()
        }

        /** 설계 6-4절 기대값 — 생성 10분 1초 뒤 */
        @DisplayName("10분 1초가 지나면 만료다 (P-32).")
        @Test
        fun isExpiredAfterTenMinutes() {
            // arrange
            val order = OrderFixture.order(userId = 1L, now = BASE_TIME)

            // assert
            assertThat(order.isExpired(BASE_TIME.plusMinutes(10).plusSeconds(1))).isTrue()
        }

        @DisplayName("만료로 표시하면 EXPIRED 가 된다 (P-32 · 배치가 부른다 — DS-4).")
        @Test
        fun marksExpired() {
            // arrange
            val order = OrderFixture.order(userId = 1L)

            // act
            order.expire()

            // assert
            assertThat(order.status).isEqualTo(OrderStatus.EXPIRED)
        }

        @DisplayName("DRAFT 가 아닌 주문은 만료로 표시하지 않는다. 확정된 주문의 만료 시각도 지나 있기 때문이다.")
        @Test
        fun rejectsExpiringNonDraft() {
            // arrange
            val order = OrderFixture.order(userId = 1L).apply { confirm(BASE_TIME) }

            // act
            val exception = assertThrows<CoreException> { order.expire() }

            // assert
            assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_NOT_DRAFT)
        }
    }
}
