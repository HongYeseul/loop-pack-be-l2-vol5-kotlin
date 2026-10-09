package com.loopers.domain.admin

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** 마스킹 해제 조회 기록, 고객 속성이 아닌 관리자 행위라 domain/admin 에 위치 */
@Entity
@Table(name = "personal_data_access_log")
class PersonalDataAccessLog(
    @Column(name = "actor_id", nullable = false, updatable = false)
    val actorId: Long,
    @Column(name = "target_user_id", nullable = false, updatable = false)
    val targetUserId: Long,
    @Column(name = "purpose", nullable = false, updatable = false, length = PURPOSE_MAX_LENGTH)
    val purpose: String,
) : BaseEntity() {
    init {
        if (purpose.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "조회 목적은 비어있을 수 없습니다.")
        }
        if (purpose.length > PURPOSE_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "조회 목적은 ${PURPOSE_MAX_LENGTH}자를 넘을 수 없습니다.")
        }
    }

    companion object {
        const val PURPOSE_MAX_LENGTH = 200
    }
}
