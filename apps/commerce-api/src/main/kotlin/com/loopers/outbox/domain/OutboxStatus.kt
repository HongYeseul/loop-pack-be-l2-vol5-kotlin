package com.loopers.outbox.domain

enum class OutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    FAILED,
}
