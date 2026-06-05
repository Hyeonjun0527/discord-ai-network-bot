package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.provider.domain.model.ProviderState
import org.springframework.stereotype.Component
import kotlin.math.max

private const val DEFAULT_COLD_START_SAMPLE_LIMIT = 3

data class Candidate(
    val providerId: Long,
    val state: ProviderState,
    val supportedBurdens: Set<ModelBurden>,
    val maxConcurrency: Int,
    val activeRequests: Int,
    val remainingDaily: Int,
    val allowedRoleIds: Set<Long>? = null,
    val allowedChannelIds: Set<Long>? = null,
    val maxPromptChars: Int = 100_000,
    val failureRate: Double = 0.0,
    val inCooldown: Boolean = false,
    val recentHandled: Int = 0,
    val modelNames: Set<String> = emptySet(),
    val qualityTier: String = ModelQualityTier.STANDARD.wire,
    val observedSuccessRate: Double = 0.94,
    val observedTimeoutRate: Double = 0.0,
    val observedLatencyMillis: Long = 0,
    val observedOutputChars: Int = 0,
    val observedSampleCount: Int = 0,
    val contextLimitTokens: Int = max(1, maxPromptChars / 4),
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = false,
    val supportsJsonMode: Boolean = false,
    val modelFamilies: Set<String> = modelNames,
    val blockedProvider: Boolean = false,
    val privacyCapabilities: Set<RoutingPrivacyPolicy> = setOf(RoutingPrivacyPolicy.STANDARD),
    val heartbeatAgeMillis: Long = 0,
    val circuitState: RoutingCircuitState =
        if (state == ProviderState.UNHEALTHY) RoutingCircuitState.OPEN else RoutingCircuitState.CLOSED,
    val trustedConcurrency: Int = maxConcurrency,
    val centralReservedQuotaUnits: Int = 0,
    val estimatedPendingPrefillTokens: Int = 0,
    val estimatedPendingDecodeTokens: Int = 0,
    val estimatedPendingWorkMillis: Double = 0.0,
    val prefillTokensPerSecondEma: Double = 600.0,
    val decodeTokensPerSecondEma: Double = 90.0,
    val networkRttEmaMillis: Double = 40.0,
    val cacheHitTokensByPrefix: Map<String, Int> = emptyMap(),
    val lambdas: RoutingLambdas = RoutingLambdas(),
)

data class RequestContext(
    val requiredBurden: ModelBurden,
    val requesterRoleIds: Set<Long>,
    val channelId: Long,
    val promptChars: Int,
    val requesterIsAdmin: Boolean = false,
    val preferredModel: String? = null,
    val responseMode: String = "balanced",
    val requestId: String = "",
    val userId: Long = 0L,
    val promptTokens: Int = max(1, promptChars / 4),
    val maxOutputTokens: Int = estimatedMaxOutputTokens(responseMode),
    val predictedOutputP50: Int = max(1, maxOutputTokens / 2),
    val predictedOutputP90: Int = max(1, (maxOutputTokens * 0.9).toInt()),
    val predictedOutputP95: Int = max(1, maxOutputTokens),
    val deadlineTtftMillis: Long = defaultDeadlineMillis(requiredBurden, responseMode) / 2,
    val deadlineTbtMillis: Long = 1_500L,
    val deadlineE2eMillis: Long = defaultDeadlineMillis(requiredBurden, responseMode),
    val requiredQualityTier: ModelQualityTier = ModelQualityTier.STANDARD,
    val allowedModelFamilies: Set<String> = emptySet(),
    val blockedProviderIds: Set<Long> = emptySet(),
    val streamingRequired: Boolean = false,
    val toolsRequired: Boolean = false,
    val jsonModeRequired: Boolean = false,
    val privacyPolicy: RoutingPrivacyPolicy = RoutingPrivacyPolicy.STANDARD,
    val prefixFingerprint: String? = null,
    val retryCount: Int = 0,
    val maxRetryCount: Int = 1,
    val priorityValue: Double = 1.0,
    val quotaReservationUnits: Int = 1,
    val highPriority: Boolean = false,
    val hedgingAllowed: Boolean = false,
)

enum class FilterSignal {
    OK,
    NONE_AVAILABLE,
    PERMISSION_DENIED,
}

data class FilterOutcome(
    val eligible: List<Candidate>,
    val dropped: Map<Long, String>,
    val signal: FilterSignal,
)

@Component
class ProviderFilterPipeline(
    private val maxFailureRate: Double = 0.5,
    private val heartbeatTimeoutMillis: Long = 90_000,
) {
    private data class Step(
        val reason: String,
        val permissionRelated: Boolean = false,
        val keep: (Candidate, RequestContext) -> Boolean,
    )

    private val steps =
        listOf(
            Step("REQUEST_TOKEN_INVALID") { _, ctx ->
                ctx.promptChars >= 0 &&
                    ctx.promptTokens > 0 &&
                    ctx.maxOutputTokens > 0 &&
                    ctx.quotaReservationUnits >= 0 &&
                    ctx.deadlineTtftMillis > 0 &&
                    ctx.deadlineTbtMillis > 0 &&
                    ctx.deadlineE2eMillis > 0
            },
            Step("PROVIDER_TELEMETRY_INVALID") { c, _ ->
                c.failureRate.isRate() &&
                    c.observedSuccessRate.isRate() &&
                    c.observedTimeoutRate.isRate() &&
                    c.heartbeatAgeMillis >= 0L &&
                    c.observedSampleCount >= 0
            },
            Step("MODEL_BURDEN_UNSUPPORTED", permissionRelated = true) { c, ctx ->
                ctx.requiredBurden in c.supportedBurdens
            },
            Step("RESTRICTED_REQUEST", permissionRelated = true) { _, ctx ->
                ctx.requiredBurden != ModelBurden.RESTRICTED || ctx.requesterIsAdmin
            },
            Step("REQUEST_BLOCKED_PROVIDER", permissionRelated = true) { c, ctx ->
                !c.blockedProvider && c.providerId !in ctx.blockedProviderIds
            },
            Step("CIRCUIT_OPEN") { c, _ ->
                c.circuitState != RoutingCircuitState.OPEN
            },
            Step("HEARTBEAT_EXPIRED") { c, _ ->
                c.heartbeatAgeMillis <= heartbeatTimeoutMillis
            },
            Step("COOLDOWN") { c, _ ->
                !c.inCooldown
            },
            Step("PROVIDER_OFFLINE") { c, _ ->
                c.state.isOnline
            },
            Step("HALF_OPEN_UNSAFE") { c, ctx ->
                c.circuitState != RoutingCircuitState.HALF_OPEN || ctx.isSafeCanaryRequest()
            },
            Step("COLD_START_UNSAFE") { c, ctx ->
                !c.isColdStart() || ctx.isSafeCanaryRequest()
            },
            Step("ROLE_MISMATCH", permissionRelated = true) { c, ctx ->
                c.allowedRoleIds == null || c.allowedRoleIds.any { it in ctx.requesterRoleIds }
            },
            Step("CHANNEL_MISMATCH", permissionRelated = true) { c, ctx ->
                c.allowedChannelIds == null || ctx.channelId in c.allowedChannelIds
            },
            Step("MODEL_FAMILY_NOT_ALLOWED", permissionRelated = true) { c, ctx ->
                ctx.allowedModelFamilies.isEmpty() ||
                    c.modelFamilies.anyIgnoreCaseIn(ctx.allowedModelFamilies)
            },
            Step("MODEL_NOT_AVAILABLE", permissionRelated = true) { c, ctx ->
                ctx.preferredModel.isNullOrBlank() ||
                    c.modelNames.any { it.equals(ctx.preferredModel, ignoreCase = true) }
            },
            Step("QUOTA_INSUFFICIENT") { c, ctx ->
                hasEnoughQuota(c, ctx)
            },
            Step("CONCURRENCY_DISABLED") { c, _ ->
                c.effectiveConcurrencyLimit() > 0
            },
            Step("CONCURRENCY_FULL") { c, _ ->
                c.activeRequests.coerceAtLeast(0) < c.effectiveConcurrencyLimit()
            },
            Step("CONTEXT_LIMIT_EXCEEDED") { c, ctx ->
                ctx.promptTokens.toLong() + ctx.maxOutputTokens.toLong() <= c.contextLimitTokens.toLong()
            },
            Step("PROMPT_CHARS_EXCEEDED") { c, ctx ->
                ctx.promptChars <= c.maxPromptChars
            },
            Step("QUALITY_TIER_INSUFFICIENT", permissionRelated = true) { c, ctx ->
                ModelQualityTier.fromWire(c.qualityTier).rank >= ctx.requiredQualityTier.rank
            },
            Step("PRIVACY_MISMATCH", permissionRelated = true) { c, ctx ->
                ctx.privacyPolicy in c.privacyCapabilities
            },
            Step("STREAMING_UNSUPPORTED") { c, ctx ->
                !ctx.streamingRequired || c.supportsStreaming
            },
            Step("TOOLS_UNSUPPORTED") { c, ctx ->
                !ctx.toolsRequired || c.supportsTools
            },
            Step("JSON_MODE_UNSUPPORTED") { c, ctx ->
                !ctx.jsonModeRequired || c.supportsJsonMode
            },
            Step("FAILURE_RATE_TOO_HIGH") { c, _ ->
                c.failureRate <= maxFailureRate
            },
        )

    private val permissionReasons: Set<String> =
        steps
            .filter { it.permissionRelated }
            .map { it.reason }
            .toSet()

    fun filter(
        candidates: List<Candidate>,
        ctx: RequestContext,
    ): FilterOutcome {
        val dropped = LinkedHashMap<Long, String>()
        val eligible =
            candidates.filter { candidate ->
                val failingStep = steps.firstOrNull { step -> !step.keep(candidate, ctx) }
                if (failingStep == null) {
                    true
                } else {
                    dropped[candidate.providerId] = failingStep.reason
                    false
                }
            }

        val signal =
            when {
                eligible.isNotEmpty() -> FilterSignal.OK
                candidates.isNotEmpty() && dropped.values.isNotEmpty() && dropped.values.all { it in permissionReasons } ->
                    FilterSignal.PERMISSION_DENIED
                else -> FilterSignal.NONE_AVAILABLE
            }

        return FilterOutcome(eligible, dropped, signal)
    }

    private fun hasEnoughQuota(
        candidate: Candidate,
        ctx: RequestContext,
    ): Boolean {
        if (candidate.remainingDaily == Int.MAX_VALUE) return true
        if (candidate.remainingDaily < 0) return false

        val available =
            candidate.remainingDaily.toLong() -
                candidate.centralReservedQuotaUnits.coerceAtLeast(0).toLong()

        return available >= ctx.quotaReservationUnits.coerceAtLeast(0).toLong()
    }
}

fun Candidate.isColdStart(): Boolean = observedSampleCount < DEFAULT_COLD_START_SAMPLE_LIMIT

fun Candidate.effectiveConcurrencyLimit(): Int {
    val base =
        minOf(maxConcurrency, trustedConcurrency)
            .coerceAtLeast(0)

    if (base == 0) return 0

    val circuitLimited =
        if (circuitState == RoutingCircuitState.HALF_OPEN) {
            minOf(base, 1)
        } else {
            base
        }

    return if (isColdStart() && activeRequests <= 0) {
        minOf(circuitLimited, 1)
    } else {
        circuitLimited
    }
}

fun RequestContext.isSafeCanaryRequest(): Boolean =
    !highPriority &&
        !hedgingAllowed &&
        priorityValue <= 1.0 &&
        requiredBurden == ModelBurden.LIGHT &&
        promptTokens in 1..1_024 &&
        maxOutputTokens in 1..1_024 &&
        deadlineE2eMillis >= 5_000L &&
        !streamingRequired &&
        !toolsRequired &&
        !jsonModeRequired

private fun Double.isRate(): Boolean = isFinite() && this in 0.0..1.0

private fun Set<String>.anyIgnoreCaseIn(other: Set<String>): Boolean =
    any { left -> other.any { right -> left.equals(right, ignoreCase = true) } }
