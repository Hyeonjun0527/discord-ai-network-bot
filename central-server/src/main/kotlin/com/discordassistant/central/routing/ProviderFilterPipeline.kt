package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.ProviderState
import org.springframework.stereotype.Component

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
)

/** 요청 라우팅 컨텍스트. */
data class RequestContext(
    val requiredBurden: ModelBurden,
    val requesterRoleIds: Set<Long>,
    val channelId: Long,
    val promptChars: Int,
    val requesterIsAdmin: Boolean = false,
    val preferredModel: String? = null,
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
) {
    private data class Step(
        val reason: String,
        val keep: (Candidate, RequestContext) -> Boolean,
    )

    private val steps =
        listOf(
            Step("burden") { c, ctx -> ctx.requiredBurden in c.supportedBurdens },
            // RESTRICTED 모델 라우팅 완성(#139): RESTRICTED 요청은 관리자만(역할/채널 게이트와 결합).
            Step("restricted") { _, ctx -> ctx.requiredBurden != ModelBurden.RESTRICTED || ctx.requesterIsAdmin },
            Step("offline") { c, _ -> c.state.isOnline },
            Step("busy") { c, _ -> c.state == ProviderState.ONLINE_IDLE },
            Step("role") { c, ctx -> c.allowedRoleIds == null || c.allowedRoleIds.any { it in ctx.requesterRoleIds } },
            Step("channel") { c, ctx -> c.allowedChannelIds == null || ctx.channelId in c.allowedChannelIds },
            Step("model") { c, ctx -> ctx.preferredModel.isNullOrBlank() || ctx.preferredModel in c.modelNames },
            Step("daily_limit") { c, _ -> c.remainingDaily > 0 },
            Step("concurrency") { c, _ -> c.activeRequests < c.maxConcurrency },
            Step("cooldown") { c, _ -> !c.inCooldown },
            Step("prompt_size") { c, ctx -> ctx.promptChars <= c.maxPromptChars },
            Step("failure_rate") { c, _ -> c.failureRate <= maxFailureRate },
        )

    // 권한성 사유(이게 마지막 탈락 이유면 PERMISSION_DENIED 신호).
    private val permissionReasons = setOf("burden", "restricted", "role", "channel")

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
}
