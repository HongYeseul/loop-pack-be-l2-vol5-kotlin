package com.loopers.coupon.application

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** 발급 취소·총량 증가가 없는 캠페인의 품절을 프로세스 수명 동안 보존 */
@Component
class CouponSoldOutCache {
    private val campaignIds = ConcurrentHashMap.newKeySet<Long>()

    fun contains(campaignId: Long): Boolean = campaignIds.contains(campaignId)

    fun remember(campaignId: Long) {
        campaignIds.add(campaignId)
    }
}
