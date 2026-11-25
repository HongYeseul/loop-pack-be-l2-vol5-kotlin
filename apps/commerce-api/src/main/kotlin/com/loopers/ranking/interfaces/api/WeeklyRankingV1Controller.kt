package com.loopers.ranking.interfaces.api

import com.loopers.ranking.application.WeeklyRankingQuery
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/api/v1/rankings")
class WeeklyRankingV1Controller(
    private val query: WeeklyRankingQuery,
) : WeeklyRankingV1ApiSpec {
    @GetMapping("/weekly")
    override fun getWeeklyRanking(
        @RequestParam("periodStart") periodStart: String,
        @RequestParam(value = "limit", required = false) limit: String?,
    ): ApiResponse<List<WeeklyRankingV1Response>> {
        val start = parsePeriodStart(periodStart)
        val requestedLimit = limit?.let {
            it.toIntOrNull() ?: throw CoreException(ErrorType.BAD_REQUEST, "limit은 정수여야 합니다.")
        } ?: WeeklyRankingQuery.DEFAULT_LIMIT

        return ApiResponse.success(
            query.get(start, requestedLimit).map {
                WeeklyRankingV1Response(it.periodStart, it.productId, it.score, it.rank)
            },
        )
    }

    private fun parsePeriodStart(value: String): LocalDate {
        if (DATE_PATTERN.matches(value)) {
            try {
                return LocalDate.parse(value)
            } catch (_: DateTimeParseException) {
                throw CoreException(ErrorType.BAD_REQUEST, "periodStart는 유효한 날짜여야 합니다.")
            }
        }
        throw CoreException(ErrorType.BAD_REQUEST, "periodStart는 YYYY-MM-DD 형식이어야 합니다.")
    }

    private companion object {
        val DATE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    }
}

data class WeeklyRankingV1Response(
    val periodStart: LocalDate,
    val productId: Long,
    val score: Long,
    val rank: Long,
)
