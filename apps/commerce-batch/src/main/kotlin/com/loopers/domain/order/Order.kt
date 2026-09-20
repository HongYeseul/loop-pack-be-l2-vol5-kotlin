package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.ZonedDateTime

/**
 * 만료 배치가 보는 만큼의 `orders` (DS-4). `commerce-api` 의 주문과 **같은 테이블, 다른 앱**이다 —
 * 두 앱이 domain 을 공유하지 않는다 (설계 1-1절).
 *
 * 배치가 하는 일이 상태 하나를 바꾸는 것뿐이라 판단에 쓰는 두 컬럼만 든다. 상태도 enum 을 복제하지
 * 않는다 — 배치가 아는 상태는 `OrderJpaRepository.expireDrafts` 의 두 문자열이 전부다.
 */
@Entity
@Table(name = "orders")
class Order(
    status: String,
    expiresAt: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: String = status
        protected set

    /** 판단 기준은 규칙(10분)이 아니라 **저장된 결과**다 (DS-4). */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: ZonedDateTime = expiresAt

    companion object {
        private const val STATUS_MAX_LENGTH = 20
    }
}
