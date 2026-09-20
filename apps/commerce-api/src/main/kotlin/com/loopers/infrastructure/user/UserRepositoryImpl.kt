package com.loopers.infrastructure.user

import com.loopers.domain.user.LoginId
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class UserRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
) : UserRepository {
    override fun findByLoginId(loginId: LoginId): User? = userJpaRepository.findByLoginIdValue(loginId.value)

    override fun findById(id: Long): User? = userJpaRepository.findByIdOrNull(id)
}
