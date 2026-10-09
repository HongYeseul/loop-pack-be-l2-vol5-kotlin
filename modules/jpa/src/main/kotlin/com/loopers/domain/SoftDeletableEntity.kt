package com.loopers.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.ZonedDateTime

/** 논리 삭제 엔티티, 삭제 컬럼 유무를 타입으로 구분 (컬럼이 없으면 delete 호출이 컴파일되지 않음) */
@MappedSuperclass
abstract class SoftDeletableEntity : BaseEntity() {
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null
        protected set

    /** 이미 삭제된 엔티티를 다시 삭제해도 결과 동일 */
    fun delete() {
        deletedAt ?: run { deletedAt = ZonedDateTime.now() }
    }

    /** 삭제되지 않은 엔티티를 복원해도 결과 동일 */
    fun restore() {
        deletedAt?.let { deletedAt = null }
    }
}
