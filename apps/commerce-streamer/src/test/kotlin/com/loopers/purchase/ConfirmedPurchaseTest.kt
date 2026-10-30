package com.loopers.purchase

import com.loopers.purchase.domain.PurchaseActivity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("구매 사실과 저장된 활동의 내용 비교")
class ConfirmedPurchaseTest {
    @Test
    @DisplayName("저장된 품목 순서가 달라도 전체 내용이 같으면 일치한다")
    fun matchesReorderedActivities() {
        val activities = listOf(PurchaseActivity(42, 8, 1, PurchaseEvents.time), PurchaseActivity(42, 7, 2, PurchaseEvents.time))

        assertThat(PurchaseEvents.purchase().matches(activities)).isTrue()
    }

    @Test
    @DisplayName("저장된 품목이 일부만 있으면 일치하지 않는다")
    fun rejectsPartialActivities() {
        val activities = listOf(PurchaseActivity(42, 7, 2, PurchaseEvents.time))

        assertThat(PurchaseEvents.purchase().matches(activities)).isFalse()
    }
}
