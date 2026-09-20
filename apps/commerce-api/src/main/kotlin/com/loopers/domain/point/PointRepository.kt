package com.loopers.domain.point

/** 사용자마다 한 행이다 (`UNIQUE(user_id)`). 그래서 찾는 길도 `userId` 하나뿐이다. */
interface PointRepository {
    fun save(point: Point): Point

    fun findByUserId(userId: Long): Point?
}
