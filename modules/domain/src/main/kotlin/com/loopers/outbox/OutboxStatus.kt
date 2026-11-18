package com.loopers.outbox

enum class OutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    FAILED,
}
