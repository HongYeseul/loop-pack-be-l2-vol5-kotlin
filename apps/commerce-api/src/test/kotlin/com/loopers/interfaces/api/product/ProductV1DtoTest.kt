package com.loopers.interfaces.api.product

import com.loopers.domain.product.ProductStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 고객에게 보이는 판매 상태 넷을 **저장된 셋과 재고에서** 만든다 (P-37 · DS-11).
 *
 * 스프링 컨텍스트가 필요 없다. 여기서 확인하는 것은 판정 **순서**뿐이고,
 * 그 순서가 규칙이다 — 단종 → 판매중지 → 재고없음 → 판매중.
 */
class ProductV1DtoTest {
    @DisplayName("고객에게 보일 판매 상태를 정할 때,")
    @Nested
    inner class SaleStatusOf {
        @DisplayName("판매중이고 재고가 있으면, 판매중이다.")
        @Test
        fun onSale() {
            assertThat(ProductV1Dto.SaleStatus.of(ProductStatus.ON_SALE, stock = 1))
                .isEqualTo(ProductV1Dto.SaleStatus.ON_SALE)
        }

        @DisplayName("판매중인데 재고가 0 이면, 재고없음이다. 저장된 상태가 아니라 재고에서 따라온다 (P-37).")
        @Test
        fun soldOut() {
            assertThat(ProductV1Dto.SaleStatus.of(ProductStatus.ON_SALE, stock = 0))
                .isEqualTo(ProductV1Dto.SaleStatus.SOLD_OUT)
        }

        @DisplayName("판매중지면, 재고가 있든 없든 판매중지다. 재고없음보다 먼저 판정한다 (P-37).")
        @ParameterizedTest
        @ValueSource(ints = [0, 5])
        fun suspendedBeatsSoldOut(stock: Int) {
            assertThat(ProductV1Dto.SaleStatus.of(ProductStatus.SUSPENDED, stock))
                .isEqualTo(ProductV1Dto.SaleStatus.SUSPENDED)
        }

        @DisplayName("단종이면, 무엇보다 먼저 단종이다 (P-37 · 판정 순서의 맨 앞).")
        @ParameterizedTest
        @ValueSource(ints = [0, 5])
        fun discontinuedBeatsEverything(stock: Int) {
            assertThat(ProductV1Dto.SaleStatus.of(ProductStatus.DISCONTINUED, stock))
                .isEqualTo(ProductV1Dto.SaleStatus.DISCONTINUED)
        }
    }
}
