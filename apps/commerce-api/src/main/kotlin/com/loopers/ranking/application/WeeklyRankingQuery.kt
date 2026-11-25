package com.loopers.ranking.application

import com.loopers.ranking.domain.WeeklyRankingItem
import com.loopers.ranking.domain.WeeklyRankingRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

@Component
class WeeklyRankingQuery(
    private val repository: WeeklyRankingRepository,
) {
    fun get(periodStart: LocalDate, limit: Int): List<WeeklyRankingItem> {
        if (periodStart.dayOfWeek != DayOfWeek.MONDAY) {
            throw CoreException(ErrorType.BAD_REQUEST, "periodStart는 월요일이어야 합니다.")
        }
        if (limit !in 1..MAX_LIMIT) {
            throw CoreException(ErrorType.BAD_REQUEST, "limit은 1 이상 ${MAX_LIMIT} 이하여야 합니다.")
        }
        return repository.findRankedItems(periodStart, periodStart.minusWeeks(1), limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 100
    }
}
