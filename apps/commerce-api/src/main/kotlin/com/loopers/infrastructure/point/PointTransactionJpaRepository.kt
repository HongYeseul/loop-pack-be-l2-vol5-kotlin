package com.loopers.infrastructure.point

import com.loopers.domain.point.PointTransaction
import org.springframework.data.jpa.repository.JpaRepository

/** 읽는 메서드를 두지 않는다 — 원장을 읽는 화면이 아직 없다 (DS-12). */
interface PointTransactionJpaRepository : JpaRepository<PointTransaction, Long>
