package com.loopers.outbox.domain

enum class OutboxStatus {
    PENDING,

    /** relay 가 선점해 전송 중, claimed_until 이 지나면 다른 relay 가 다시 선점 */
    CLAIMED,

    PUBLISHED,

    /** 재시도 한도 초과 또는 다시 해도 안 되는 실패, 같은 key 의 뒤 행도 멈춤 */
    FAILED,
}
