package com.loopers.domain.point

/**
 * **`save` 밖에 없다** (DS-12). 원장은 append-only 라 고치거나 지우는 길을 두지 않는다 —
 * 없는 메서드는 실수로 불릴 수도 없다.
 *
 * 읽는 길도 아직 없다. 원장을 읽는 화면은 이번 범위 밖이고(환불은 기획 3-2절),
 * 지금 필요한 것은 **기록을 남기는 것**뿐이다. `balance == SUM(amount)` 는 통합 테스트가 DB 에서 직접 확인한다.
 */
interface PointTransactionRepository {
    fun save(transaction: PointTransaction): PointTransaction
}
