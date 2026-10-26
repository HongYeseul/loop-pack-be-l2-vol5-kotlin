package com.loopers.brand.domain

import com.loopers.domain.SoftDeletableEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** 상품 묶음 이름, Product 컬렉션은 두지 않음 (연결 여부는 상품 쪽 조회) */
@Entity
@Table(name = "brand")
class Brand(
    name: String,
) : SoftDeletableEntity() {
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name
        protected set

    /** BaseEntity.id 의 별칭, 호출부에서 무엇의 id 인지 구분 */
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
