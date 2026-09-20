package com.loopers.domain.brand

import com.loopers.domain.SoftDeletableEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 상품을 묶는 이름 (기획 4절). 카테고리가 아니라, 상품 하나가 속하는 곳 하나다.
 *
 * `Product` 컬렉션을 갖지 않는다 (설계 2-3절). P-11("살아 있는 상품이 연결된 브랜드는
 * 못 지운다")은 컬렉션을 순회해야 답하는 질문이 아니라 **있냐 없냐** 를 묻는 질문이라,
 * 상품 쪽에 물어보는 것으로 끝난다.
 *
 * **논리 삭제 대상이다** (D-2). 지난 주문의 상품이 어느 브랜드였는지 남아야 한다.
 * 그래서 `deletedAt` 을 가진 [SoftDeletableEntity] 를 상속한다 (DS-10).
 */
@Entity
@Table(name = "brand")
class Brand(
    name: String,
) : SoftDeletableEntity() {
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name
        protected set

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
