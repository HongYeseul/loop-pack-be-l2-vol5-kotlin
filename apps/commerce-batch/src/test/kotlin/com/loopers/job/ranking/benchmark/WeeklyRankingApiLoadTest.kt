package com.loopers.job.ranking.benchmark

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig
import com.loopers.batch.job.ranking.WeeklyRankingPeriod
import com.loopers.batch.job.ranking.benchmark.step.score.RankingBenchmarkScoreStepConfiguration
import com.loopers.batch.job.ranking.step.publish.WeeklyRankingPublishStepConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.Step
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.io.path.writeText

@EnabledIfEnvironmentVariable(named = "RUN_RANKING_API_LOAD", matches = "true")
@SpringJUnitConfig(WeeklyRankingScoreBenchmarkConfig::class)
@ActiveProfiles(RankingBenchmarkScoreStepConfiguration.PROFILE)
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklyRankingJobConfig.JOB_NAME}"])
class WeeklyRankingApiLoadTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val launcher: JobLauncher,
    private val jobRepository: JobRepository,
    private val steps: Map<String, Step>,
    private val mysql: MySQLContainer<*>,
    @param:Qualifier(WeeklyRankingPublishStepConfiguration.STEP_NAME) private val publishStep: Step,
) {
    @Test
    fun `주문 확정 부하 중 구매 행과 상품 ID 집계가 API에 미치는 영향을 측정한다`() {
        val purchases = System.getenv("RANKING_LOAD_PURCHASES")?.toInt() ?: 1_000_000
        val rate = System.getenv("RANKING_LOAD_RATE")?.toInt() ?: 30
        val workload = RankingBenchmarkWorkload(1_000, purchases, skewed = false, order = PurchaseOrder.GROUPED)
        require(purchases >= workload.products)
        val period = WeeklyRankingPeriod(LocalDate.of(2040, 1, 2), 20_000)
        val fixture = RankingBenchmarkFixture(jdbc, period)
        val reportRoot = Path.of("build", "weekly-ranking-api-load")
        Files.createDirectories(reportRoot)
        val output = Files.createTempDirectory(reportRoot, "run-").toAbsolutePath()
        val methods = listOf(RankingBenchmarkMethod.A, RankingBenchmarkMethod.B)
        val harness = RankingApiLoadHarness(jdbc, mysql, output, rate)

        fixture.seedFrom(workload)
        assertThat(fixture.freeze()).isEqualTo(purchases)
        harness.startApi().use { api ->
            val loaded = methods.associateWith { method ->
                fixture.clearResults()
                harness.measure(api, method.name) {
                    val score = steps.getValue(method.stepName)
                    lateinit var window: RankingLoadWindow
                    val measuredStep = object : Step by score {
                        override fun execute(stepExecution: StepExecution) {
                            val before = harness.counters(api)
                            val start = Instant.now()
                            score.execute(stepExecution)
                            val end = Instant.now()
                            window = RankingLoadWindow(start, end, harness.counters(api) - before)
                        }
                    }
                    val job = JobBuilder("RANKING_API_LOAD_${method.name}", jobRepository)
                        .start(measuredStep)
                        .next(publishStep)
                        .build()
                    val params = JobParametersBuilder()
                        .addLocalDate("periodStart", period.start)
                        .addLong("revision", period.revision)
                        .toJobParameters()
                    assertCompleted(launcher.run(job, params))
                    listOf(window)
                }.single().also {
                    assertThat(fixture.resultCount()).isEqualTo(workload.products.toLong())
                    assertThat(fixture.countResultMismatches()).isZero()
                }
            }

            val ordered = loaded.entries.sortedBy { it.value.window.duration }
            fixture.clearResults()
            val baseline = harness.measure(api, "api-only") {
                val before = harness.counters(api)
                val start = Instant.now()
                ordered.map { (_, result) ->
                    val targetEnd = start.plus(result.window.duration)
                    val remaining = Duration.between(Instant.now(), targetEnd)
                    if (!remaining.isNegative) Thread.sleep(remaining)
                    val counters = harness.counters(api) - before
                    RankingLoadWindow(start, targetEnd, counters)
                }
            }
            val baselineByMethod = ordered.map { it.key }.zip(baseline).toMap()
            output.resolve("results.csv").writeText(
                buildString {
                    appendLine(RankingLoadResult.CSV_HEADER)
                    for (method in methods) {
                        appendLine(baselineByMethod.getValue(method).toCsv("API_ONLY_${method.name}"))
                        appendLine(loaded.getValue(method).toCsv(method.name))
                    }
                },
            )
            output.resolve("environment.txt").writeText(
                "workload=${workload.id}\norder_rate_per_second=$rate\n" +
                    "mysql=${jdbc.queryForObject("SELECT VERSION()", String::class.java)}\n",
            )
        }
        println("주간 랭킹과 주문 확정 부하 비교 결과 $output")
    }
}
