package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ModelQualityTier
import com.discordassistant.central.domain.ProviderState
import org.springframework.stereotype.Component
import kotlin.math.max

/** 라우팅 후보(세션+정책에서 router 가 구성). 파이프라인은 이 추상에만 의존(순수 테스트 가능). */
data class Candidate(
    val providerId: Long,
    val state: ProviderState,
    val supportedBurdens: Set<ModelBurden>,
    val maxConcurrency: Int,
    val activeRequests: Int,
    val remainingDaily: Int,
    val allowedRoleIds: Set<Long>? = null, // null = 전체 허용
    val allowedChannelIds: Set<Long>? = null, // null = 전체 허용
    val maxPromptChars: Int = 100_000,
    val failureRate: Double = 0.0,
    val inCooldown: Boolean = false,
    val recentHandled: Int = 0,
    val modelNames: Set<String> = emptySet(),
    val qualityTier: String = "standard",
    val observedSuccessRate: Double = 0.94,
    val observedTimeoutRate: Double = 0.0,
    val observedLatencyMillis: Long = 0,
    val observedOutputChars: Int = 0,
    val observedSampleCount: Int = 0,
    val contextLimitTokens: Int = maxPromptChars / 4,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = false,
    val supportsJsonMode: Boolean = false,
    val modelFamilies: Set<String> = modelNames,
    val blockedProvider: Boolean = false,
    val privacyCapabilities: Set<RoutingPrivacyPolicy> = setOf(RoutingPrivacyPolicy.STANDARD),
    val heartbeatAgeMillis: Long = 0,
    val circuitState: RoutingCircuitState = if (state == ProviderState.UNHEALTHY) RoutingCircuitState.OPEN else RoutingCircuitState.CLOSED,
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

/** 요청 라우팅 컨텍스트. */
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
    val predictedOutputP95: Int = maxOutputTokens,
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

enum class FilterSignal { OK, NONE_AVAILABLE, PERMISSION_DENIED }

data class FilterOutcome(
    val eligible: List<Candidate>,
    val dropped: Map<Long, String>,
    val signal: FilterSignal,
)

/**
 * Provider Pool 필터 파이프라인 (K-차수 9, specs §8 선택 기준 10가지). 후보를 단계별로 거른다.
 * 각 후보가 떨어진 첫 사유를 기록한다.
 */
@Component
class ProviderFilterPipeline(
    private val maxFailureRate: Double = 0.5,
    private val heartbeatTimeoutMillis: Long = 90_000,
) {
    private data class Step(
        val reason: String,
        val keep: (Candidate, RequestContext) -> Boolean,
    )

    private val steps =
        listOf(
            Step("MODEL_BURDEN_UNSUPPORTED") { c, ctx -> ctx.requiredBurden in c.supportedBurdens },
            Step("RESTRICTED_REQUEST") { _, ctx -> ctx.requiredBurden != ModelBurden.RESTRICTED || ctx.requesterIsAdmin },
            Step("REQUEST_BLOCKED_PROVIDER") { c, ctx -> !c.blockedProvider && c.providerId !in ctx.blockedProviderIds },
            Step("CIRCUIT_OPEN") { c, _ -> c.circuitState != RoutingCircuitState.OPEN },
            Step("HEARTBEAT_EXPIRED") { c, _ -> c.heartbeatAgeMillis <= heartbeatTimeoutMillis },
            Step("COOLDOWN") { c, _ -> !c.inCooldown },
            Step("PROVIDER_OFFLINE") { c, _ -> c.state.isOnline },
            Step("HALF_OPEN_UNSAFE") { c, ctx -> c.circuitState != RoutingCircuitState.HALF_OPEN || ctx.isSafeCanaryRequest() },
            Step("COLD_START_UNSAFE") { c, ctx -> c.observedSampleCount >= COLD_START_SAMPLE_LIMIT || ctx.isSafeCanaryRequest() },
            Step("ROLE_MISMATCH") { c, ctx -> c.allowedRoleIds == null || c.allowedRoleIds.any { it in ctx.requesterRoleIds } },
            Step("CHANNEL_MISMATCH") { c, ctx -> c.allowedChannelIds == null || ctx.channelId in c.allowedChannelIds },
            Step("MODEL_FAMILY_NOT_ALLOWED") { c, ctx ->
                val requestedFamilies = ctx.allowedModelFamilies
                requestedFamilies.isEmpty() || c.modelFamilies.any { it in requestedFamilies }
            },
            Step("MODEL_NOT_AVAILABLE") { c, ctx -> ctx.preferredModel.isNullOrBlank() || ctx.preferredModel in c.modelNames },
            Step("QUOTA_INSUFFICIENT") { c, ctx ->
                c.remainingDaily == Int.MAX_VALUE ||
                    c.remainingDaily - c.centralReservedQuotaUnits >= ctx.quotaReservationUnits
            },
            Step("CONCURRENCY_FULL") { c, _ -> c.activeRequests < c.effectiveConcurrencyLimit() },
            Step("CONTEXT_LIMIT_EXCEEDED") { c, ctx -> ctx.promptTokens + ctx.maxOutputTokens <= c.contextLimitTokens },
            Step("QUALITY_TIER_INSUFFICIENT") { c, ctx ->
                ModelQualityTier.fromWire(c.qualityTier).rank >= ctx.requiredQualityTier.rank
            },
            Step("PRIVACY_MISMATCH") { c, ctx -> ctx.privacyPolicy in c.privacyCapabilities },
            Step("STREAMING_UNSUPPORTED") { c, ctx -> !ctx.streamingRequired || c.supportsStreaming },
            Step("TOOLS_UNSUPPORTED") { c, ctx -> !ctx.toolsRequired || c.supportsTools },
            Step("JSON_MODE_UNSUPPORTED") { c, ctx -> !ctx.jsonModeRequired || c.supportsJsonMode },
            Step("FAILURE_RATE_TOO_HIGH") { c, _ -> c.failureRate <= maxFailureRate },
        )

    // 권한성 사유(이게 마지막 탈락 이유면 PERMISSION_DENIED 신호).
    private val permissionReasons =
        setOf(
            "MODEL_BURDEN_UNSUPPORTED",
            "RESTRICTED_REQUEST",
            "ROLE_MISMATCH",
            "CHANNEL_MISMATCH",
            "QUALITY_TIER_INSUFFICIENT",
            "PRIVACY_MISMATCH",
            "MODEL_FAMILY_NOT_ALLOWED",
            "MODEL_NOT_AVAILABLE",
            "REQUEST_BLOCKED_PROVIDER",
        )

    fun filter(
        candidates: List<Candidate>,
        ctx: RequestContext,
    ): FilterOutcome {
        val dropped = LinkedHashMap<Long, String>()
        val eligible =
            candidates.filter { c ->
                val failing = steps.firstOrNull { !it.keep(c, ctx) }
                if (failing != null) {
                    dropped[c.providerId] = failing.reason
                    false
                } else {
                    true
                }
            }
        val signal =
            when {
                eligible.isNotEmpty() -> FilterSignal.OK
                candidates.isNotEmpty() && dropped.values.all { it in permissionReasons } ->
                    FilterSignal.PERMISSION_DENIED
                else -> FilterSignal.NONE_AVAILABLE
            }
        return FilterOutcome(eligible, dropped, signal)
    }

    companion object {
        private const val COLD_START_SAMPLE_LIMIT = 3
    }
}

fun Candidate.effectiveConcurrencyLimit(): Int = minOf(maxConcurrency.coerceAtLeast(1), trustedConcurrency.coerceAtLeast(1))

fun RequestContext.isSafeCanaryRequest(): Boolean =
    !highPriority &&
        requiredBurden == ModelBurden.LIGHT &&
        promptTokens <= 1_024 &&
        maxOutputTokens <= 1_024 &&
        deadlineE2eMillis >= 5_000L &&
        !streamingRequired &&
        !toolsRequired &&
        !jsonModeRequired
