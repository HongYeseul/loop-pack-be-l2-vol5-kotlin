package com.loopers.infrastructure.point

import com.loopers.domain.point.PointTransaction
import org.springframework.data.jpa.repository.JpaRepository

/** 읽는 메서드 없음 (원장 조회 화면 미구현) */
interface PointTransactionJpaRepository : JpaRepository<PointTransaction, Long>
