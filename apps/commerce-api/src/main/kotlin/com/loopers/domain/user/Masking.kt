package com.loopers.domain.user

/** 마스킹 규칙을 한 곳에 둠 (LoginId·User 불일치 방지) */
internal fun masked(value: String): String =
    if (value.length <= 1) "*" else value.take(1) + "*".repeat(value.length - 1)
