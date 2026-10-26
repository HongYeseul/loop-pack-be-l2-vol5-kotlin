package com.loopers.user.infrastructure

import com.loopers.user.domain.LoginId
import com.loopers.user.domain.User
import com.loopers.user.domain.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class UserRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
) : UserRepository {
    override fun findByLoginId(loginId: LoginId): User? = userJpaRepository.findByLoginIdValue(loginId.value)

    override fun findById(id: Long): User? = userJpaRepository.findByIdOrNull(id)
}
