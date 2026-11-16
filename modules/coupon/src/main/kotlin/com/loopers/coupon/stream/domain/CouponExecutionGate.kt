package com.loopers.coupon.stream.domain

data class CouponGateStatus(
    val accepting: Boolean,
    val activeTokens: Set<String>,
    val epoch: String?,
    val reason: String?,
)

interface CouponExecutionGate {
    fun status(): CouponGateStatus
    fun close(): CouponGateStatus
    fun recover(confirmed: Boolean, expectedTokens: Set<String> = emptySet()): CouponGateStatus
    fun acquire(): String?
    fun release(token: String)
    fun retainUncertain(token: String)
}
