package com.loopers.coupon.stream.domain

data class CouponReservation(val streamId: String, val created: Boolean)
data class CouponStreamMessage(val id: String, val campaignId: Long, val userId: Long)
data class CouponStreamStatus(val quota: Int, val reserved: Long, val entries: Long, val pending: Long)

enum class CouponStreamFailure { CLOSED, FULL, UNAVAILABLE, STATE_LOST, RECOVERY_CONFLICT, INVALID_REQUEST }

class CouponStreamException(val failure: CouponStreamFailure, cause: Throwable? = null) :
    RuntimeException(failure.name, cause)

interface CouponStream {
    fun initializeCampaign(campaignId: Long, quota: Int, issuedUsers: List<Long> = emptyList())
    fun reserve(campaignId: Long, userId: Long): CouponReservation
    fun reservation(campaignId: Long, userId: Long): String?
    fun ensureGroup(campaignId: Long, group: String)
    fun read(campaignId: Long, group: String, consumer: String, blockMs: Long = 500): CouponStreamMessage?
    fun readPending(campaignId: Long, group: String, consumer: String): CouponStreamMessage?
    fun claimPending(campaignId: Long, group: String, consumer: String, minIdleMs: Long = 1000): CouponStreamMessage?
    fun acknowledge(campaignId: Long, group: String, messageId: String)
    fun status(campaignId: Long, group: String): CouponStreamStatus
}
