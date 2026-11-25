package com.loopers.batch.job.ranking

import org.springframework.batch.core.JobParameters
import java.time.DayOfWeek
import java.time.LocalDate

data class WeeklyRankingPeriod(val start: LocalDate, val revision: Long) {
    init {
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "periodStart는 월요일이어야 합니다" }
        require(revision > 0) { "revision은 양수여야 합니다" }
    }

    val snapshotId: String = "weekly-$start-r$revision"

    companion object {
        fun from(parameters: JobParameters): WeeklyRankingPeriod {
            require(parameters.identifyingParameters.keys == setOf("periodStart", "revision")) {
                "식별 파라미터는 periodStart와 revision이어야 합니다"
            }
            return WeeklyRankingPeriod(
                requireNotNull(parameters.getLocalDate("periodStart")) { "periodStart가 필요합니다" },
                requireNotNull(parameters.getLong("revision")) { "revision이 필요합니다" },
            )
        }
    }
}
