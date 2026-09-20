package com.loopers.domain.product

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * 판매 상태의 전이 규칙 (P-36 · DS-11).
 *
 * **판매중지와 단종을 나누는 유일한 이유가 여기 있다.** 둘 다 "못 산다"는 같고,
 * 다른 것은 **관리자가 다음에 할 수 있는 일**이다 — 판매중지는 되돌리고, 단종은 못 되돌린다.
 * 그 차이가 전이 규칙으로 나타나지 않으면 상태를 둘로 나눌 이유도 없다.
 */
class ProductStatusTest {
    @DisplayName("판매 상태를 바꿀 때,")
    @Nested
    inner class Transition {
        @DisplayName("판매중과 판매중지는 서로 오갈 수 있다 (P-36 · 되돌릴 수 있다).")
        @Test
        fun allowsBothWaysBetweenOnSaleAndSuspended() {
            assertAll(
                { assertThat(ProductStatus.ON_SALE.canTransitionTo(ProductStatus.SUSPENDED)).isTrue() },
                { assertThat(ProductStatus.SUSPENDED.canTransitionTo(ProductStatus.ON_SALE)).isTrue() },
            )
        }

        @DisplayName("어느 상태에서든 단종으로는 갈 수 있다.")
        @ParameterizedTest
        @EnumSource(ProductStatus::class)
        fun allowsDiscontinueFromAnywhere(from: ProductStatus) {
            assertThat(from.canTransitionTo(ProductStatus.DISCONTINUED)).isTrue()
        }

        @DisplayName("단종에서는 어디로도 돌아가지 못한다 (P-36 · 최종 상태).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class, names = ["ON_SALE", "SUSPENDED"])
        fun rejectsAnyWayBackFromDiscontinued(to: ProductStatus) {
            assertThat(ProductStatus.DISCONTINUED.canTransitionTo(to)).isFalse()
        }

        @DisplayName("같은 상태로의 설정은 언제나 허용한다. A-16 은 전이가 아니라 **최종 상태 설정**이다 (설계 6-3절).")
        @ParameterizedTest
        @EnumSource(ProductStatus::class)
        fun allowsSettingTheSameStatus(status: ProductStatus) {
            assertThat(status.canTransitionTo(status)).isTrue()
        }
    }

    @DisplayName("판매중인지 물을 때,")
    @Nested
    inner class IsOnSale {
        @DisplayName("ON_SALE 만 참이다. 재고는 보지 않는다 — 재고없음은 다른 축이다 (P-37).")
        @Test
        fun onlyOnSaleIsTrue() {
            assertAll(
                { assertThat(ProductStatus.ON_SALE.isOnSale).isTrue() },
                { assertThat(ProductStatus.SUSPENDED.isOnSale).isFalse() },
                { assertThat(ProductStatus.DISCONTINUED.isOnSale).isFalse() },
            )
        }
    }
}
