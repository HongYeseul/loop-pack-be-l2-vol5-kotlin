package com.loopers.point.infrastructure

import com.loopers.point.domain.Point
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface PointJpaRepository : JpaRepository<Point, Long> {
    fun findByUserId(userId: Long): Point?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByUserId(userId: Long): Point?
}
