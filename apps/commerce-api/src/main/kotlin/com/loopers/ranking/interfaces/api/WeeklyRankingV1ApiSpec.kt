package com.loopers.ranking.interfaces.api

import com.loopers.support.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Weekly Ranking V1 API", description = "고객용 주간 구매 랭킹 API")
interface WeeklyRankingV1ApiSpec {
    @Operation(
        summary = "주간 구매 랭킹 조회",
        description = "요청한 주에 순위가 있으면 그 주의 랭킹을, 없으면 직전 주의 랭킹을 반환합니다. " +
            "각 항목의 periodStart로 실제 조회된 주를 확인할 수 있습니다. 두 주 모두 순위가 없으면 빈 배열을 반환합니다.",
    )
    fun getWeeklyRanking(
        @Parameter(description = "UTC 기준 월요일", schema = Schema(type = "string", format = "date", example = "2026-08-17"))
        periodStart: String,
        @Parameter(
            description = "조회할 최대 개수",
            schema = Schema(type = "integer", defaultValue = "10", minimum = "1", maximum = "100"),
        )
        limit: String?,
    ): ApiResponse<List<WeeklyRankingV1Response>>
}
