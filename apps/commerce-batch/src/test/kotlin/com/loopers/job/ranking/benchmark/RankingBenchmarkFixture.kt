package com.loopers.job.ranking.benchmark

import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.benchmark.step.score.PurchaseRow
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.random.Random

internal class RankingBenchmarkFixture(private val jdbc: JdbcTemplate, val period: WeeklyRankingPeriod) {
    fun seed(purchases: Sequence<PurchaseRow>) {
        jdbc.execute("TRUNCATE TABLE purchase_activity")
        purchases.withIndex().chunked(1_000).forEach { batch ->
            jdbc.batchUpdate(
                "INSERT INTO purchase_activity (order_id, product_id, quantity, confirmed_at) VALUES (?, ?, ?, ?)",
                batch,
                batch.size,
            ) { statement, (index, purchase) ->
                statement.setLong(1, index + 1L)
                statement.setLong(2, purchase.productId)
                statement.setInt(3, purchase.quantity)
                statement.setObject(4, period.start.atStartOfDay())
            }
        }
    }

    fun freeze(): Int =
        jdbc.update(
            """
            INSERT INTO weekly_ranking_input (snapshot_id, purchase_id, order_id, product_id, quantity, confirmed_at)
            SELECT ?, id, order_id, product_id, quantity, confirmed_at
            FROM purchase_activity
            WHERE confirmed_at >= ? AND confirmed_at < ?
            """.trimIndent(),
            period.snapshotId,
            period.start.atStartOfDay(),
            period.start.plusWeeks(1).atStartOfDay(),
        )

    fun clearResults() {
        jdbc.execute("TRUNCATE TABLE weekly_ranking_result")
    }

    fun clearInput() {
        jdbc.execute("TRUNCATE TABLE weekly_ranking_input")
        jdbc.execute("TRUNCATE TABLE purchase_activity")
    }

    fun resultCount(): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM weekly_ranking_result WHERE snapshot_id = ?",
                Long::class.javaObjectType,
                period.snapshotId,
            ),
        )

    fun results(): List<RankedScore> =
        jdbc.query(
            """
            SELECT product_id, score, rank_position
            FROM weekly_ranking_result
            WHERE snapshot_id = ?
            ORDER BY rank_position, product_id
            """.trimIndent(),
            { row, _ ->
                RankedScore(
                    row.getLong("product_id"),
                    row.getLong("score"),
                    row.getObject("rank_position", Long::class.javaObjectType),
                )
            },
            period.snapshotId,
        )

    fun countResultMismatches(): Long =
        requireNotNull(
            jdbc.queryForObject(
                """
                WITH expected AS (
                    SELECT product_id, SUM(quantity) AS score,
                        ROW_NUMBER() OVER (ORDER BY SUM(quantity) DESC, product_id ASC) AS rank_position
                    FROM weekly_ranking_input
                    WHERE snapshot_id = ?
                    GROUP BY product_id
                )
                SELECT COUNT(*)
                FROM expected
                LEFT JOIN weekly_ranking_result AS result ON result.snapshot_id = ? AND result.product_id = expected.product_id
                WHERE result.product_id IS NULL OR result.score <> expected.score
                    OR result.rank_position IS NULL OR result.rank_position <> expected.rank_position
                """.trimIndent(),
                Long::class.javaObjectType,
                period.snapshotId,
                period.snapshotId,
            ),
        )

    fun seedFrom(workload: RankingBenchmarkWorkload) {
        val random = Random(SEED)
        val quantities = ByteArray(workload.purchases) { random.nextInt(1, 6).toByte() }
        val order = if (workload.order == PurchaseOrder.SHUFFLED) {
            IntArray(workload.purchases) { it }.apply { shuffle(Random(SEED)) }
        } else {
            null
        }
        seed(
            quantities.indices.asSequence().map { position ->
                val index = order?.get(position) ?: position
                PurchaseRow(workload.productIdAt(index).toLong(), quantities[index].toInt())
            },
        )
    }

    companion object {
        const val SEED = 42
    }
}

internal data class RankedScore(val productId: Long, val score: Long, val rank: Long?)

internal enum class PurchaseOrder { GROUPED, SHUFFLED }

internal data class RankingBenchmarkWorkload(
    val products: Int,
    val purchases: Int,
    val skewed: Boolean,
    val order: PurchaseOrder,
) {
    private val hotProducts = products / 100
    private val hotPurchases = purchases * 80 / 100
    val id = "${products}x$purchases-${if (skewed) "skewed" else "uniform"}-${order.name.lowercase()}"
    val supportsDistribution = !skewed || purchases - hotPurchases >= products - hotProducts

    fun productIdAt(index: Int): Int =
        when {
            !skewed -> 1 + productOffset(index, purchases, products)
            index < hotPurchases -> 1 + productOffset(index, hotPurchases, hotProducts)
            else -> 1 + hotProducts + productOffset(index - hotPurchases, purchases - hotPurchases, products - hotProducts)
        }

    private fun productOffset(index: Int, rows: Int, productCount: Int): Int {
        val base = rows / productCount
        val withExtraRow = rows % productCount
        val extraRows = withExtraRow * (base + 1)
        return if (index < extraRows) index / (base + 1) else withExtraRow + (index - extraRows) / base
    }
}
