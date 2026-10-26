package com.loopers.point.domain

/** 사용자당 한 행 (UNIQUE) */
interface PointRepository {
    fun save(point: Point): Point

    fun findByUserId(userId: Long): Point?

    /** 충전과 사용이 같은 잔액 행을 읽고 바꾸므로 잠금 */
    fun findByUserIdForUpdate(userId: Long): Point?
}
