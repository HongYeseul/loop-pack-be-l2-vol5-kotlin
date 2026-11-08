package com.loopers.outbox.infrastructure

import com.loopers.outbox.domain.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventJpaRepository : JpaRepository<OutboxEvent, Long>
