package com.loopers.domain.point

/** 사용자당 한 행 (UNIQUE) */
interface PointRepository {
    fun save(point: Point): Point

    fun findByUserId(userId: Long): Point?
}
