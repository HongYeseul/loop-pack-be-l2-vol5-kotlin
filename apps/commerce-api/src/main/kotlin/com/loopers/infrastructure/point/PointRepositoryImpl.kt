package com.loopers.infrastructure.point

import com.loopers.domain.point.Point
import com.loopers.domain.point.PointRepository
import org.springframework.stereotype.Component

@Component
class PointRepositoryImpl(
    private val pointJpaRepository: PointJpaRepository,
) : PointRepository {
    override fun save(point: Point): Point = pointJpaRepository.save(point)

    override fun findByUserId(userId: Long): Point? = pointJpaRepository.findByUserId(userId)
}
