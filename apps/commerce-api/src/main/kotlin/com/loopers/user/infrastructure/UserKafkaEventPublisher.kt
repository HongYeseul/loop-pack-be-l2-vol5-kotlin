package com.loopers.user.infrastructure

import com.loopers.user.domain.event.UserEventType

class UserKafkaEventPublisher {
    fun publishSignedUp(userId: Long) {
        // TODO: 가입 commit 후 전달된 userId로 UserEventType.USER_SIGNED_UP을 구성해 UserEventType.TOPIC으로 발행
        TODO("${UserEventType.USER_SIGNED_UP.eventName} 발행 미구현: userId=$userId")
    }
}
