package com.loopers.ranking.domain

import java.time.LocalDate

interface WeeklyRankingRepository {
    fun findRankedItems(periodStart: LocalDate, previousPeriodStart: LocalDate, limit: Int): List<WeeklyRankingItem>
}

data class WeeklyRankingItem(
    val periodStart: LocalDate,
    val productId: Long,
    val score: Long,
    val rank: Long,
)
