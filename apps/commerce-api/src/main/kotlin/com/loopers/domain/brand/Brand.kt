package com.loopers.domain.brand

import com.loopers.domain.SoftDeletableEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 상품을 묶는 이름 (기획 4절). 상품 하나가 속하는 곳 하나다.
 *
 * `Product` 컬렉션을 갖지 않는다 (설계 2-3절). P-11 은 **있냐 없냐** 를 묻는 질문이라
 * 상품 쪽에 물어보면 끝난다.
 *
 * 논리 삭제 대상이라 [SoftDeletableEntity] 를 상속한다 (D-2 · DS-10).
 */
@Entity
@Table(name = "brand")
class Brand(
    name: String,
) : SoftDeletableEntity() {
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name
        protected set

    /** 건네줄 때 쓰는 이름. `BaseEntity.id` 와 같은 값인데 호출부에서 무엇의 id 인지 보인다 (DS-13). */
    val brandId: Long get() = id

    init {
        guardName(name)
    }

    fun changeName(newName: String) {
        guardName(newName)
        this.name = newName
    }

    private fun guardName(name: String) {
        if (name.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 비어있을 수 없습니다.")
        }
        if (name.length > NAME_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 ${NAME_MAX_LENGTH}자를 넘을 수 없습니다.")
        }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
    }
}
