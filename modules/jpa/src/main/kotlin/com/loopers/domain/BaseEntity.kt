package com.loopers.domain

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import java.time.ZonedDateTime

/** 모든 엔티티 공통 식별자·생성/수정 시각, 삭제 정보는 SoftDeletableEntity 담당 */
@MappedSuperclass
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: ZonedDateTime
        protected set

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: ZonedDateTime
        protected set

    /** PrePersist·PreUpdate 시점에 호출되는 유효성 검증 훅 */
    open fun guard() = Unit

    @PrePersist
    private fun prePersist() {
        guard()

        val now = ZonedDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    private fun preUpdate() {
        guard()

        val now = ZonedDateTime.now()
        updatedAt = now
    }
}
