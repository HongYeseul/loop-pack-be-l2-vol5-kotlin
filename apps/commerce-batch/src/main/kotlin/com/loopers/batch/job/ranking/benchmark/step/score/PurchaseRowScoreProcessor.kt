package com.loopers.batch.job.ranking.benchmark.step.score

import com.loopers.batch.job.ranking.step.score.WeeklyProductScore

data class PurchaseRow(val productId: Long, val quantity: Int)

class PurchaseRowScoreProcessor {
    fun process(snapshotId: String, purchases: List<PurchaseRow>): List<WeeklyProductScore> =
        purchases.groupingBy(PurchaseRow::productId)
            .fold(0L) { score, purchase -> score + purchase.quantity }
            .map { (productId, score) -> WeeklyProductScore(snapshotId, productId, score) }
}
