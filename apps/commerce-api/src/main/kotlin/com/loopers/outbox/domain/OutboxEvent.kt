package com.loopers.outbox.domain

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.ZonedDateTime

@Entity
@Table(
    name = "outbox",
    uniqueConstraints = [
        // 같은 사실의 중복 기록 차단
        UniqueConstraint(name = "uk_outbox_event_id", columnNames = ["event_id"]),
    ],
    indexes = [
        // relay 후보 조회
        Index(name = "idx_outbox_status_next_attempt", columnList = "status, next_attempt_at"),
        // 같은 key 의 앞선 행 확인
        Index(name = "idx_outbox_aggregate", columnList = "aggregate_type, aggregate_id, id"),
    ],
)
class OutboxEvent(
    eventId: String,
    aggregateType: String,
    aggregateId: String,
    eventType: String,
    schemaVersion: Int,
    topic: String,
    payload: String,
    now: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "event_id", nullable = false, length = EVENT_ID_MAX_LENGTH)
    val eventId: String = eventId

    @Column(name = "aggregate_type", nullable = false, length = NAME_MAX_LENGTH)
    val aggregateType: String = aggregateType

    @Column(name = "aggregate_id", nullable = false, length = NAME_MAX_LENGTH)
    val aggregateId: String = aggregateId

    @Column(name = "event_type", nullable = false, length = NAME_MAX_LENGTH)
    val eventType: String = eventType

    @Column(name = "schema_version", nullable = false)
    val schemaVersion: Int = schemaVersion

    @Column(name = "topic", nullable = false, length = NAME_MAX_LENGTH)
    val topic: String = topic

    /** json 타입은 key 순서·공백을 바꿔 저장하므로 text */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    val payload: String = payload

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
    var status: OutboxStatus = OutboxStatus.PENDING
        protected set

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
        protected set

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: ZonedDateTime = now
        protected set

    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    var lastError: String? = null
        protected set

    @Column(name = "claimed_by", length = NAME_MAX_LENGTH)
    var claimedBy: String? = null
        protected set

    @Column(name = "claimed_until")
    var claimedUntil: ZonedDateTime? = null
        protected set

    @Column(name = "published_at")
    var publishedAt: ZonedDateTime? = null
        protected set

    companion object {
        const val EVENT_ID_MAX_LENGTH = 200
        const val NAME_MAX_LENGTH = 100
        const val STATUS_MAX_LENGTH = 20
        const val LAST_ERROR_MAX_LENGTH = 1000
    }
}
