package com.loopers.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.ZonedDateTime

/**
 * 논리 삭제되는 엔티티.
 *
 * **삭제 방식이 타입으로 갈린다.** 지워도 기록이 남아야 하는 것만 이 클래스를 상속하고,
 * 나머지는 [BaseEntity] 를 상속해 `deletedAt` 자체를 갖지 않는다.
 *
 * 왜 나누나: 모두에게 `deletedAt` 을 주면 **쓰면 안 되는 엔티티에서도 [delete] 가 호출된다.**
 * 그 호출은 조용히 성공하고, 그 행을 걸러내는 조회가 없으므로 아무도 눈치채지 못한 채
 * 데이터가 틀어진다. 컬럼이 없으면 그 호출은 컴파일되지 않는다.
 *
 * @property deletedAt 삭제 시점
 */
@MappedSuperclass
abstract class SoftDeletableEntity : BaseEntity() {
    @Column(name = "deleted_at")
    var deletedAt: ZonedDateTime? = null
        protected set

    /**
     * delete 연산은 멱등하게 동작할 수 있도록 한다. (삭제된 엔티티를 다시 삭제해도 동일한 결과가 나오도록)
     */
    fun delete() {
        deletedAt ?: run { deletedAt = ZonedDateTime.now() }
    }

    /**
     * restore 연산은 멱등하게 동작할 수 있도록 한다. (삭제되지 않은 엔티티를 복원해도 동일한 결과가 나오도록)
     */
    fun restore() {
        deletedAt?.let { deletedAt = null }
    }
}
