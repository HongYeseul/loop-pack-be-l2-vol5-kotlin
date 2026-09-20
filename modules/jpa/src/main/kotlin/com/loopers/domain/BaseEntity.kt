package com.loopers.domain

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import java.time.ZonedDateTime

/**
 * 모든 엔티티가 갖는 식별자와 생성/수정 정보.
 *
 * **삭제 정보는 여기 없다.** 논리 삭제가 필요한 엔티티만 [SoftDeletableEntity] 를 상속한다.
 * 재사용성을 위해 이 외의 컬럼이나 동작은 추가하지 않는다.
 *
 * @property id 엔티티 ID
 * @property createdAt 생성 시점
 * @property updatedAt 수정 시점
 */
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

    /**
     * 엔티티의 유효성을 검증한다.
     *
     * 이 메소드는 [PrePersist] 및 [PreUpdate] 시점에 호출된다.
     */
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
