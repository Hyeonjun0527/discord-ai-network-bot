package com.discordassistant.central.routing

import com.discordassistant.central.provider.domain.model.ProviderState
import com.discordassistant.central.routing.domain.model.RoutingLambdas
import com.discordassistant.central.routing.domain.service.Candidate
import com.discordassistant.central.routing.domain.service.ProviderFilterPipeline
import com.discordassistant.central.routing.domain.service.ProviderRouter
import com.discordassistant.central.routing.domain.service.RequestContext
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.ModelQualityTier
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 보고서용 라우팅 시뮬레이션 벤치마크.
 *
 * Ours 경로는 실제 ProviderFilterPipeline + ProviderRouter 구현을 그대로 호출한다.
 * Baseline 은 capability-only round-robin 비교 기준이다.
 */
class RoutingSimulationBenchmarkReportTest {
    private val pipeline = ProviderFilterPipeline()
    private val router = ProviderRouter()

    @Test
    fun `policy router simulation benchmark report`() {
        val rawRows =
            poolSizes.flatMap { poolSize ->
                (0 until SEEDS).flatMap { seed ->
                    val baseSeed = 20260605 + poolSize * 100 + seed
                    Policy.entries.map { policy -> simulate(policy = policy, poolSize = poolSize, seed = baseSeed) }
                }
            }
        val summaries =
            rawRows
                .groupBy { it.policy to it.poolSize }
                .map { (key, rows) -> Summary.from(key.first, key.second, rows) }
                .sortedWith(compareBy<Summary> { it.policy.label }.thenBy { it.poolSize })

        val outDir = Path.of("build", "reports", "routing-simulation-benchmark")
        Files.createDirectories(outDir)
        Files.writeString(outDir.resolve("raw.csv"), rawCsv(rawRows))
        Files.writeString(outDir.resolve("summary.csv"), summaryCsv(summaries))

        val expectedBaselineLabels =
            setOf(
                "Random",
                "Round-robin",
                "Least-active",
                "Least-predicted-work",
                "Static weighted score",
                "MaxWeight-style backlog",
                "UCB reliability router",
                "Token fairness scheduler",
                "Cache-locality-only router",
                "HALO-GF without fairness",
                "HALO-GF without quota price",
                "HALO-GF without failure penalty",
                "HALO-GF full",
            )
        assertTrue(summaries.map { it.policy.label }.toSet().containsAll(expectedBaselineLabels))

        val oursAt12 = summaries.single { it.policy == Policy.OURS && it.poolSize == 12 }
        val roundRobinAt12 = summaries.single { it.policy == Policy.ROUND_ROBIN && it.poolSize == 12 }
        val randomAt12 = summaries.single { it.policy == Policy.RANDOM && it.poolSize == 12 }
        val designMetricWins =
            listOf(
                oursAt12.sloGoodput > randomAt12.sloGoodput,
                oursAt12.overloadAvoidance > roundRobinAt12.overloadAvoidance,
                oursAt12.fairness > roundRobinAt12.fairness,
                oursAt12.fallbackRecovery > 0.0,
                oursAt12.modelFit > randomAt12.modelFit,
            ).count { it }
        assertTrue(
            designMetricWins >= 3,
            "HALO-GF 설계 목표 metric 개선 수가 부족함: wins=$designMetricWins, ours=$oursAt12, random=$randomAt12, roundRobin=$roundRobinAt12",
        )
        assertTrue(oursAt12.sloSuccess >= 99.0, "HALO-GF SLO attainment 이 낮음: ours=${oursAt12.sloSuccess}")
        assertTrue(
            oursAt12.overloadAvoidance > roundRobinAt12.overloadAvoidance,
            "HALO-GF 과부하 회피율이 round-robin 보다 낮음: ours=${oursAt12.overloadAvoidance}, roundRobin=${roundRobinAt12.overloadAvoidance}",
        )
        assertTrue(
            oursAt12.fairness > roundRobinAt12.fairness,
            "HALO-GF 공정성 점수가 round-robin 보다 낮음: ours=${oursAt12.fairness}, roundRobin=${roundRobinAt12.fairness}",
        )
        assertTrue(
            oursAt12.fallbackRecovery > 0.0,
            "정책 라우터의 fallback 회복률이 0임: ours=${oursAt12.fallbackRecovery}",
        )
        assertTrue(
            oursAt12.modelFit > randomAt12.modelFit,
            "HALO-GF 모델 적합도가 random 보다 낮음: ours=${oursAt12.modelFit}, random=${randomAt12.modelFit}",
        )
    }

    private fun simulate(
        policy: Policy,
        poolSize: Int,
        seed: Int,
    ): SimulationRow {
        val rng = Random(seed)
        val providers = makeProviders(poolSize, rng)
        val requests = makeRequests(rng)
        var roundRobinPointer = 0
        var completed = 0
        var sloSuccess = 0
        var rejected = 0
        var firstFailures = 0
        var fallbackSuccess = 0
        var overloadEvents = 0
        val latencyScores = mutableListOf<Double>()
        val modelFitScores = mutableListOf<Double>()
        var lightRequests = 0
        var lightRequestsPreservingHeavy = 0

        requests.forEach { request ->
            providers.forEach { it.advanceTo(request.arrival) }

            val selection =
                when (policy) {
                    Policy.OURS,
                    Policy.HALO_WITHOUT_FAIRNESS,
                    Policy.HALO_WITHOUT_QUOTA_PRICE,
                    Policy.HALO_WITHOUT_FAILURE_PENALTY,
                    -> selectWithProductionRouter(providers, request, excluded = emptySet(), policy = policy)
                    Policy.RANDOM -> providers.filter { it.capabilityMatches(request) }.randomOrNull(rng)
                    Policy.ROUND_ROBIN -> {
                        val selected = selectBaseline(providers, request, roundRobinPointer)
                        roundRobinPointer = selected.nextPointer
                        selected.provider
                    }
                    Policy.LEAST_ACTIVE ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .minByOrNull { it.activeLoad() }
                    Policy.LEAST_PREDICTED_WORK ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .minByOrNull { it.predictedWork(request) }
                    Policy.STATIC_WEIGHTED ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .maxByOrNull { it.staticWeightedScore(request) }
                    Policy.MAX_WEIGHT_BACKLOG ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .minByOrNull { it.backlogPressure() }
                    Policy.UCB_RELIABILITY ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .maxByOrNull { it.ucbReliabilityScore() }
                    Policy.TOKEN_FAIRNESS ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .minByOrNull { it.normalizedBurden() }
                    Policy.CACHE_LOCALITY_ONLY ->
                        providers
                            .filter { it.capabilityMatches(request) }
                            .maxWithOrNull(
                                compareBy<SimProvider> { it.localityScore(request) }
                                    .thenByDescending { -it.activeLoad() },
                            )
                }
            if (selection == null) {
                rejected += 1
                return@forEach
            }

            val firstAttempt = selection.attempt(request, rng)
            overloadEvents += firstAttempt.overloadEvents
            var acceptedAttempt = firstAttempt
            if (!firstAttempt.ok) {
                firstFailures += 1
                if (policy.isHaloFamily()) {
                    val fallback =
                        selectWithProductionRouter(
                            providers,
                            request,
                            excluded = setOf(selection.id),
                            policy = policy,
                        )
                    if (fallback != null) {
                        val retry = fallback.attempt(request, rng)
                        overloadEvents += retry.overloadEvents
                        if (retry.ok) {
                            fallbackSuccess += 1
                            acceptedAttempt = retry.copy(latency = firstAttempt.latency + retry.latency)
                        }
                    }
                }
            }

            if (acceptedAttempt.ok) {
                completed += 1
                if (acceptedAttempt.latency <= SLO_THRESHOLD_SECONDS) sloSuccess += 1
                latencyScores += 100.0 / (1.0 + acceptedAttempt.latency / LATENCY_SCALE_SECONDS)
                modelFitScores += modelFitScore(selection, request)
                if (request.burden == ModelBurden.LIGHT) {
                    lightRequests += 1
                    if (selection.maxBurden != ModelBurden.HEAVY) {
                        lightRequestsPreservingHeavy += 1
                    }
                }
            }
        }

        val totalAttempts = providers.sumOf { it.attempted }
        return SimulationRow(
            policy = policy,
            poolSize = poolSize,
            sloGoodput = completed.percentOf(requests.size) * sloSuccess.percentOf(max(1, completed)) / 100.0,
            completionRate = completed.percentOf(requests.size),
            sloSuccess = sloSuccess.percentOf(max(1, completed)),
            latencyScore = latencyScores.averageOrZero(),
            fairness = jainIndex(providers.map { it.normalizedBurden() }),
            overloadAvoidance = 100.0 - overloadEvents.percentOf(max(1, totalAttempts)),
            fallbackRecovery = fallbackSuccess.percentOf(max(1, firstFailures)),
            modelFit = modelFitScores.averageOrZero(),
            heavyPreservation = lightRequestsPreservingHeavy.percentOf(max(1, lightRequests)),
            routableRequestRate = (requests.size - rejected).percentOf(requests.size),
        )
    }

    private fun selectWithProductionRouter(
        providers: List<SimProvider>,
        request: SimRequest,
        excluded: Set<Long>,
        policy: Policy = Policy.OURS,
    ): SimProvider? {
        val candidates =
            providers
                .filterNot { it.id in excluded }
                .map { it.toCandidate().withPolicyLambdas(policy) }
        val ctx =
            RequestContext(
                requiredBurden = request.burden,
                requesterRoleIds = setOf(MEMBER_ROLE_ID),
                channelId = CHANNEL_ID,
                promptChars = request.promptChars,
                preferredModel = request.preferredModel,
            )
        val eligible = pipeline.filter(candidates, ctx).eligible
        val selection = router.select(eligible, ctx) ?: return null
        return providers.single { it.id == selection.providerId }
    }

    private fun Candidate.withPolicyLambdas(policy: Policy): Candidate =
        copy(
            lambdas =
                when (policy) {
                    Policy.HALO_WITHOUT_FAIRNESS -> RoutingLambdas(fairness = 0.0)
                    Policy.HALO_WITHOUT_QUOTA_PRICE -> RoutingLambdas(quota = 0.0)
                    Policy.HALO_WITHOUT_FAILURE_PENALTY -> RoutingLambdas(failure = 0.0)
                    else -> RoutingLambdas()
                },
        )

    private fun selectBaseline(
        providers: List<SimProvider>,
        request: SimRequest,
        pointer: Int,
    ): BaselineSelection {
        repeat(providers.size) { step ->
            val index = (pointer + step) % providers.size
            val provider = providers[index]
            if (provider.capabilityMatches(request)) {
                return BaselineSelection(provider, (index + 1) % providers.size)
            }
        }
        return BaselineSelection(null, pointer)
    }

    private data class SimProvider(
        val id: Long,
        val maxBurden: ModelBurden,
        val supportedBurdens: Set<ModelBurden>,
        val modelNames: Set<String>,
        val maxConcurrency: Int,
        val speed: Double,
        val baseFailureRate: Double,
        val maxPromptChars: Int,
        val dailyLimit: Int,
        val qualityTier: ModelQualityTier,
    ) {
        private val scheduledFinishes = mutableListOf<Double>()
        private var protectionUntil = 0.0
        private var recentHandledScore = 0.0
        private var remainingDaily = dailyLimit
        private var clock = 0.0
        var attempted = 0
            private set
        var completed = 0
            private set
        private var completedBurden = 0.0

        fun advanceTo(now: Double) {
            clock = now
            scheduledFinishes.removeIf { it <= now }
            recentHandledScore *= RECENT_DECAY
        }

        fun toCandidate(): Candidate =
            Candidate(
                providerId = id,
                state = if (scheduledFinishes.isEmpty()) ProviderState.ONLINE_IDLE else ProviderState.ONLINE_BUSY,
                supportedBurdens = supportedBurdens,
                maxConcurrency = maxConcurrency,
                activeRequests = scheduledFinishes.size,
                remainingDaily = remainingDaily,
                maxPromptChars = maxPromptChars,
                failureRate = baseFailureRate,
                inCooldown = protectionUntil > clock,
                recentHandled = recentHandledScore.roundToInt(),
                modelNames = modelNames,
                qualityTier = qualityTier.wire,
                observedSuccessRate = (1.0 - baseFailureRate).coerceIn(0.05, 0.99),
                observedLatencyMillis = observedLatencyPriorMillis(),
                observedSampleCount = max(12, attempted),
                estimatedPendingWorkMillis = scheduledFinishes.sumOf { max(0.0, it - clock) * 1_000.0 },
                prefillTokensPerSecondEma = 500.0 * speed,
                decodeTokensPerSecondEma = 80.0 * speed,
            )

        fun capabilityMatches(request: SimRequest): Boolean =
            request.burden in supportedBurdens &&
                request.promptChars <= maxPromptChars &&
                remainingDaily > 0 &&
                (request.preferredModel == null || request.preferredModel in modelNames)

        fun attempt(
            request: SimRequest,
            rng: Random,
        ): AttemptResult {
            val now = request.arrival
            advanceTo(now)
            attempted += 1
            remainingDaily = max(0, remainingDaily - 1)
            val activeBefore = scheduledFinishes.size
            val queueDelay =
                if (activeBefore < maxConcurrency) {
                    0.0
                } else {
                    max(0.0, (scheduledFinishes.minOrNull() ?: now) - now)
                }
            val overload =
                when {
                    activeBefore >= maxConcurrency + MAX_QUEUE_DEPTH -> 3
                    activeBefore >= maxConcurrency -> 1
                    else -> 0
                }
            val serviceTime = serviceTime(request, rng)
            val start = now + queueDelay
            scheduledFinishes += start + serviceTime
            recentHandledScore += 1.0
            if (overload > 0) protectionUntil = max(protectionUntil, now + PROTECTION_SECONDS)

            val pressurePenalty = min(0.42, queueDelay / 18.0 + overload * 0.08)
            val successProbability = (1.0 - baseFailureRate - pressurePenalty).coerceIn(0.25, 0.99)
            val ok = rng.nextDouble() < successProbability
            if (ok) {
                completed += 1
                completedBurden += request.burden.rank.toDouble()
            }
            return AttemptResult(ok = ok, latency = queueDelay + serviceTime, overloadEvents = overload)
        }

        fun normalizedBurden(): Double = completedBurden / (dailyLimit * maxConcurrency * speed).coerceAtLeast(1.0)

        fun activeLoad(): Int = scheduledFinishes.size

        fun backlogPressure(): Double = activeLoad().toDouble() / maxConcurrency.coerceAtLeast(1)

        fun predictedWork(request: SimRequest): Double = scheduledFinishes.sumOf { max(0.0, it - clock) } + serviceTimeEstimate(request)

        fun staticWeightedScore(request: SimRequest): Double =
            modelFitScore(this, request) / 100.0 -
                backlogPressure() * 0.30 -
                baseFailureRate * 0.60 -
                normalizedBurden() * 0.20

        fun ucbReliabilityScore(): Double = 1.0 - baseFailureRate + kotlin.math.sqrt(1.0 / (attempted + 1.0))

        fun localityScore(request: SimRequest): Int =
            when {
                request.preferredModel != null && request.preferredModel in modelNames -> 2
                request.preferredModel == null -> 1
                else -> 0
            }

        private fun serviceTime(
            request: SimRequest,
            rng: Random,
        ): Double {
            val base =
                when (request.burden) {
                    ModelBurden.LIGHT -> 1.25
                    ModelBurden.STANDARD -> 2.65
                    ModelBurden.HEAVY -> 4.75
                    ModelBurden.RESTRICTED -> 6.25
                }
            return base * rng.nextDouble(0.82, 1.22) / speed
        }

        private fun serviceTimeEstimate(request: SimRequest): Double {
            val base =
                when (request.burden) {
                    ModelBurden.LIGHT -> 1.25
                    ModelBurden.STANDARD -> 2.65
                    ModelBurden.HEAVY -> 4.75
                    ModelBurden.RESTRICTED -> 6.25
                }
            return base / speed
        }

        private fun observedLatencyPriorMillis(): Long =
            (
                when (maxBurden) {
                    ModelBurden.LIGHT -> 1_400.0
                    ModelBurden.STANDARD -> 2_800.0
                    ModelBurden.HEAVY -> 5_200.0
                    ModelBurden.RESTRICTED -> 7_000.0
                } / speed
            ).roundToInt().toLong()
    }

    private data class SimRequest(
        val arrival: Double,
        val burden: ModelBurden,
        val preferredModel: String?,
        val promptChars: Int,
    )

    private data class AttemptResult(
        val ok: Boolean,
        val latency: Double,
        val overloadEvents: Int,
    )

    private data class BaselineSelection(
        val provider: SimProvider?,
        val nextPointer: Int,
    )

    private data class SimulationRow(
        val policy: Policy,
        val poolSize: Int,
        val sloGoodput: Double,
        val completionRate: Double,
        val sloSuccess: Double,
        val latencyScore: Double,
        val fairness: Double,
        val overloadAvoidance: Double,
        val fallbackRecovery: Double,
        val modelFit: Double,
        val heavyPreservation: Double,
        val routableRequestRate: Double,
    )

    private data class Summary(
        val policy: Policy,
        val poolSize: Int,
        val sloGoodput: Double,
        val completionRate: Double,
        val sloSuccess: Double,
        val latencyScore: Double,
        val fairness: Double,
        val overloadAvoidance: Double,
        val fallbackRecovery: Double,
        val modelFit: Double,
        val heavyPreservation: Double,
        val routableRequestRate: Double,
    ) {
        companion object {
            fun from(
                policy: Policy,
                poolSize: Int,
                rows: List<SimulationRow>,
            ): Summary =
                Summary(
                    policy = policy,
                    poolSize = poolSize,
                    sloGoodput = rows.map { it.sloGoodput }.average(),
                    completionRate = rows.map { it.completionRate }.average(),
                    sloSuccess = rows.map { it.sloSuccess }.average(),
                    latencyScore = rows.map { it.latencyScore }.average(),
                    fairness = rows.map { it.fairness }.average(),
                    overloadAvoidance = rows.map { it.overloadAvoidance }.average(),
                    fallbackRecovery = rows.map { it.fallbackRecovery }.average(),
                    modelFit = rows.map { it.modelFit }.average(),
                    heavyPreservation = rows.map { it.heavyPreservation }.average(),
                    routableRequestRate = rows.map { it.routableRequestRate }.average(),
                )
        }
    }

    private enum class Policy(
        val label: String,
    ) {
        RANDOM("Random"),
        ROUND_ROBIN("Round-robin"),
        LEAST_ACTIVE("Least-active"),
        LEAST_PREDICTED_WORK("Least-predicted-work"),
        STATIC_WEIGHTED("Static weighted score"),
        MAX_WEIGHT_BACKLOG("MaxWeight-style backlog"),
        UCB_RELIABILITY("UCB reliability router"),
        TOKEN_FAIRNESS("Token fairness scheduler"),
        CACHE_LOCALITY_ONLY("Cache-locality-only router"),
        HALO_WITHOUT_FAIRNESS("HALO-GF without fairness"),
        HALO_WITHOUT_QUOTA_PRICE("HALO-GF without quota price"),
        HALO_WITHOUT_FAILURE_PENALTY("HALO-GF without failure penalty"),
        OURS("HALO-GF full"),
    }

    private fun Policy.isHaloFamily(): Boolean =
        this == Policy.OURS ||
            this == Policy.HALO_WITHOUT_FAIRNESS ||
            this == Policy.HALO_WITHOUT_QUOTA_PRICE ||
            this == Policy.HALO_WITHOUT_FAILURE_PENALTY

    companion object {
        private val poolSizes = listOf(2, 3, 4, 5, 6, 8, 10, 12)
        private val models = listOf("llama3", "mistral", "qwen", "gemma")
        private const val SEEDS = 4
        private const val REQUEST_COUNT = 500
        private const val ARRIVAL_RATE = 1.75
        private const val MEMBER_ROLE_ID = 1L
        private const val CHANNEL_ID = 200L
        private const val MAX_QUEUE_DEPTH = 8
        private const val PROTECTION_SECONDS = 8.0
        private const val RECENT_DECAY = 0.996
        private const val SLO_THRESHOLD_SECONDS = 8.0
        private const val LATENCY_SCALE_SECONDS = 4.0

        private fun makeProviders(
            poolSize: Int,
            rng: Random,
        ): List<SimProvider> =
            (0 until poolSize).map { index ->
                val maxBurden =
                    when {
                        index == poolSize - 1 -> ModelBurden.HEAVY
                        rng.nextDouble() < 0.25 -> ModelBurden.LIGHT
                        rng.nextDouble() < 0.70 -> ModelBurden.STANDARD
                        else -> ModelBurden.HEAVY
                    }
                val modelCount =
                    when (maxBurden) {
                        ModelBurden.LIGHT -> 1
                        ModelBurden.STANDARD -> if (rng.nextDouble() < 0.55) 2 else 1
                        ModelBurden.HEAVY -> if (rng.nextDouble() < 0.35) 3 else 2
                        ModelBurden.RESTRICTED -> 1
                    }
                val maxConcurrency =
                    when (maxBurden) {
                        ModelBurden.LIGHT -> 1
                        ModelBurden.STANDARD -> if (rng.nextDouble() < 0.45) 2 else 1
                        ModelBurden.HEAVY -> if (rng.nextDouble() < 0.60) 2 else 1
                        ModelBurden.RESTRICTED -> 1
                    }
                SimProvider(
                    id = (index + 1).toLong(),
                    maxBurden = maxBurden,
                    supportedBurdens = supportedBurdens(maxBurden),
                    modelNames = models.shuffled(rng).take(modelCount).toSet(),
                    maxConcurrency = maxConcurrency,
                    speed = rng.nextDouble(0.82, 1.28) * (1.0 + maxBurden.rank * 0.08),
                    baseFailureRate = rng.nextDouble(0.02, 0.13) + if (maxBurden == ModelBurden.HEAVY) 0.015 else 0.0,
                    maxPromptChars =
                        when (maxBurden) {
                            ModelBurden.LIGHT -> 8_000
                            ModelBurden.STANDARD -> 32_000
                            ModelBurden.HEAVY -> 100_000
                            ModelBurden.RESTRICTED -> 100_000
                        },
                    dailyLimit = 120 + rng.nextInt(0, 80),
                    qualityTier =
                        when (maxBurden) {
                            ModelBurden.LIGHT -> ModelQualityTier.STANDARD
                            ModelBurden.STANDARD -> ModelQualityTier.HIGH
                            ModelBurden.HEAVY -> ModelQualityTier.SPECIALIZED
                            ModelBurden.RESTRICTED -> ModelQualityTier.SPECIALIZED
                        },
                )
            }

        private fun makeRequests(rng: Random): List<SimRequest> {
            var t = 0.0
            return (0 until REQUEST_COUNT).map {
                t += exponential(rng, ARRIVAL_RATE)
                val burden =
                    when {
                        rng.nextDouble() < 0.50 -> ModelBurden.LIGHT
                        rng.nextDouble() < 0.70 -> ModelBurden.STANDARD
                        else -> ModelBurden.HEAVY
                    }
                val preferredModel = if (rng.nextDouble() < 0.38) models.random(rng) else null
                val promptBase =
                    when (burden) {
                        ModelBurden.LIGHT -> 1_800
                        ModelBurden.STANDARD -> 8_500
                        ModelBurden.HEAVY -> 28_000
                        ModelBurden.RESTRICTED -> 36_000
                    }
                SimRequest(
                    arrival = t,
                    burden = burden,
                    preferredModel = preferredModel,
                    promptChars = (promptBase * rng.nextDouble(0.55, 1.75)).roundToInt(),
                )
            }
        }

        private fun supportedBurdens(maxBurden: ModelBurden): Set<ModelBurden> =
            ModelBurden.entries.filter { it.rank <= maxBurden.rank && it != ModelBurden.RESTRICTED }.toSet()

        private fun exponential(
            rng: Random,
            rate: Double,
        ): Double = -kotlin.math.ln(1.0 - rng.nextDouble()) / rate

        private fun jainIndex(values: List<Double>): Double {
            val sum = values.sum().toDouble()
            val squares = values.sumOf { it * it }
            if (sum == 0.0 || squares == 0.0) return 0.0
            return (sum * sum) / (values.size * squares) * 100.0
        }

        private fun modelFitScore(
            provider: SimProvider,
            request: SimRequest,
        ): Double =
            when {
                provider.maxBurden == request.burden -> 100.0
                provider.maxBurden.rank == request.burden.rank + 1 -> 76.0
                else -> 52.0
            }

        private fun Int.percentOf(total: Int): Double = toDouble() / total.toDouble() * 100.0

        private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

        private fun rawCsv(rows: List<SimulationRow>): String =
            buildString {
                appendLine(CSV_HEADER)
                rows.forEach { appendLine(it.toCsv()) }
            }

        private fun summaryCsv(rows: List<Summary>): String =
            buildString {
                appendLine(CSV_HEADER)
                rows.forEach { appendLine(it.toCsv()) }
            }

        private const val CSV_HEADER =
            "policy,pool_size,slo_goodput,completion_rate,slo_success,latency_score,fairness," +
                "overload_avoidance,fallback_recovery,model_fit,heavy_preservation,routable_request_rate"

        private fun SimulationRow.toCsv(): String =
            listOf(
                policy.label,
                poolSize,
                sloGoodput,
                completionRate,
                sloSuccess,
                latencyScore,
                fairness,
                overloadAvoidance,
                fallbackRecovery,
                modelFit,
                heavyPreservation,
                routableRequestRate,
            ).joinToString(",")

        private fun Summary.toCsv(): String =
            listOf(
                policy.label,
                poolSize,
                sloGoodput,
                completionRate,
                sloSuccess,
                latencyScore,
                fairness,
                overloadAvoidance,
                fallbackRecovery,
                modelFit,
                heavyPreservation,
                routableRequestRate,
            ).joinToString(",")
    }
}
