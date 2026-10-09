package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.ZonedDateTime

/** 만료 배치용 orders 투영, 판단에 쓰는 두 컬럼만 보유 (commerce-api 와 domain 을 공유하지 않음) */
@Entity
@Table(name = "orders")
class Order(
    status: String,
    expiresAt: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: String = status
        protected set

    /** 판단 기준은 규칙(10분)이 아닌 저장된 만료 시각 */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: ZonedDateTime = expiresAt

    companion object {
        private const val STATUS_MAX_LENGTH = 20
    }
}
