package com.loopers.batch.job.ranking.step.score

data class WeeklyProductScore(val snapshotId: String, val productId: Long, val score: Long)
