package com.loopers.user.domain.event

import com.fasterxml.jackson.annotation.JsonValue

enum class UserEventType(@get:JsonValue val eventName: String) {
    USER_SIGN_UP_ATTEMPTED("UserSignUpAttempted"),
    USER_SIGNED_UP("UserSignedUp"),
    ;

    companion object {
        const val TOPIC = "user-events"
    }
}
