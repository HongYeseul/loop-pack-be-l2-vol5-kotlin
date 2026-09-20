package com.loopers.domain.user

/**
 * 개인정보로 다룰 값의 기본 표현 (P-35).
 *
 * 규칙을 여기 하나만 둔다 — [LoginId.masked] 와 [User.maskedDisplayName] 이 각자 적으면 둘이 갈라진다.
 * 한 글자짜리는 앞 1자를 남기면 전부가 남으므로 통째로 가린다.
 */
internal fun masked(value: String): String =
    if (value.length <= 1) "*" else value.take(1) + "*".repeat(value.length - 1)
