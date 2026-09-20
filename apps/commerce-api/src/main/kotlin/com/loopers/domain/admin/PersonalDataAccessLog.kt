package com.loopers.domain.admin

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 마스킹 해제 조회의 기록 (P-35 · D-12 접속기록).
 *
 * **고객의 속성이 아니라 관리자의 행위라서 `domain/admin` 이다** (DS-6). 여기서 [AdminRoleHistory] 와
 * 나란히 놓이고, D-12 가 요구하는 두 기록이 한자리에 모인다.
 *
 * `createdAt` 이 곧 조회 시각이다. 행은 조회할 때 만들어지고 고쳐지지 않는다 — [AdminRoleHistory] 와 같은 모양이다.
 */
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
