package com.loopers.point.infrastructure

import com.loopers.point.domain.Point
import com.loopers.point.domain.PointRepository
import org.springframework.stereotype.Component

@Component
class PointRepositoryImpl(
    private val pointJpaRepository: PointJpaRepository,
) : PointRepository {
    override fun save(point: Point): Point = pointJpaRepository.save(point)

    override fun findByUserId(userId: Long): Point? = pointJpaRepository.findByUserId(userId)

    override fun findByUserIdForUpdate(userId: Long): Point? = pointJpaRepository.findForUpdateByUserId(userId)
}
