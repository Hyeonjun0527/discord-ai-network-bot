package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.relay.ConnectionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 라우팅이 필요로 하는 정책 일부(테스트 디커플용). PolicyService 가 구현. */
interface RoutingPolicy {
    fun isChannelAllowed(guildId: Long, channelId: Long): Boolean
    fun maxAllowedBurden(guildId: Long, memberRoleIds: Collection<Long>): ModelBurden
}

/** 프로바이더 정책 프로필(부담수준·허용·제한). DB(contribution policy) 또는 테스트 스텁이 제공. */
data class ProviderProfile(
    val supportedBurdens: Set<ModelBurden>,
    val allowedRoleIds: Set<Long>? = null,
    val allowedChannelIds: Set<Long>? = null,
    val maxPromptChars: Int = 100_000,
    val failureRate: Double = 0.0,
)

interface ProviderProfileProvider {
    fun profile(providerId: Long): ProviderProfile
}

/** 사용량/기여 기록 트리거. JPA 구현(UsageService) 또는 테스트 fake. */
interface UsageRecorder {
    fun recordSuccess(guildId: Long, userId: Long, providerId: Long, requestId: String)

    /** AiRequest 종단 상태 영속화(차수 11). 기본 no-op(테스트 fake 영향 없음). */
    fun recordRequest(
        input: AiRequestInput,
        state: RequestState,
        providerId: Long?,
        failReason: String?,
    ) {
    }

    /** 프로바이더 실패 기록(차수 11, ProviderHealth). 기본 no-op. */
    fun recordProviderFailure(providerId: Long) {}
}

/** 요청 입력. */
data class AiRequestInput(
    val guildId: Long,
    val channelId: Long,
    val userId: Long,
    val prompt: String,
    val roleIds: Set<Long>,
    val command: String = "ask",
)

/** 오케스트레이션 결과. */
data class OrchestrationResult(
    val state: RequestState,
    val text: String? = null,
    val providerId: Long? = null,
    val failReason: String? = null,
    val effectiveBurden: ModelBurden? = null,
)

/**
 * 요청 처리 오케스트레이터 (K-차수 11, specs §7 흐름). 정책→무게→후보→필터→선택→전송,
 * 실패 시 동일 조건 다른 provider 로 1회 fallback, 성공 시 사용량/기여 기록.
 */
@Service
class RequestOrchestrator(
    private val registry: ConnectionRegistry,
    private val policy: RoutingPolicy,
    private val weigher: RequestWeigher,
    private val pipeline: ProviderFilterPipeline,
    private val router: ProviderRouter,
    private val recorder: UsageRecorder,
    private val profiles: ProviderProfileProvider,
) {
    private val log = LoggerFactory.getLogger(RequestOrchestrator::class.java)

    fun handle(input: AiRequestInput): OrchestrationResult {
        val result = route(input)
        recorder.recordRequest(input, result.state, result.providerId, result.failReason)
        return result
    }

    private fun route(input: AiRequestInput): OrchestrationResult {
        // 1) 정책 확인(채널)
        if (!policy.isChannelAllowed(input.guildId, input.channelId)) {
            return OrchestrationResult(RequestState.REJECTED, failReason = "이 채널에서는 LLM 을 사용할 수 없습니다.")
        }
        // 2) 무게 판단 & 필요 수준(권한 상한 반영)
        val memberMax = policy.maxAllowedBurden(input.guildId, input.roleIds)
        val weigh = weigher.resolve(RequestMeta(input.prompt.length, 0, input.command), memberMax)
        if (weigh.decision == WeighDecision.REJECT) {
            return OrchestrationResult(
                RequestState.REJECTED,
                failReason = "이 요청은 ${weigh.requiredBurden} 수준이 필요하지만 현재 권한으로는 사용할 수 없습니다.",
            )
        }
        val ctx = RequestContext(weigh.effectiveBurden!!, input.roleIds, input.channelId, input.prompt.length)

        // 3) 후보 구성 + 필터 + 선택 + 전송(최대 2회: 원 + fallback 1회)
        val excluded = mutableSetOf<Long>()
        var lastReason = "처리 가능한 커뮤니티 로컬 AI 가 없습니다."
        repeat(2) { attempt ->
            val candidates = registry.byGuild(input.guildId)
                .filter { it.providerId !in excluded }
                .map { session ->
                    val p = profiles.profile(session.providerId)
                    Candidate(
                        providerId = session.providerId,
                        state = session.state,
                        supportedBurdens = p.supportedBurdens,
                        maxConcurrency = session.capability.maxConcurrency,
                        activeRequests = session.activeRequests,
                        remainingDaily = session.remainingDailyRequests,
                        allowedRoleIds = p.allowedRoleIds,
                        allowedChannelIds = p.allowedChannelIds,
                        maxPromptChars = p.maxPromptChars,
                        failureRate = p.failureRate,
                    )
                }
            val outcome = pipeline.filter(candidates, ctx)
            if (outcome.eligible.isEmpty()) {
                return if (outcome.signal == FilterSignal.PERMISSION_DENIED) {
                    OrchestrationResult(RequestState.REJECTED, failReason = "권한 또는 정책상 처리할 수 없습니다.")
                } else {
                    OrchestrationResult(RequestState.FAILED, failReason = lastReason)
                }
            }
            val sel = router.select(outcome.eligible, ctx)!!
            val session = registry.byProvider(sel.providerId)
            if (session == null) {
                excluded.add(sel.providerId)
                return@repeat
            }
            if (attempt > 0) log.info("fallback 시도 → provider {}", sel.providerId)
            try {
                // 반환 future 는 세션 orTimeout 으로 항상 시한 내 완료/실패한다 → get() 안전.
                val result = session.sendInfer(prompt = input.prompt).get()
                recorder.recordSuccess(input.guildId, input.userId, sel.providerId, requestId = result.requestId)
                return OrchestrationResult(RequestState.COMPLETED, result.text, sel.providerId, effectiveBurden = ctx.requiredBurden)
            } catch (e: Exception) {
                lastReason = e.cause?.message ?: e.message ?: "처리 실패"
                excluded.add(sel.providerId) // 실패 provider 일시 제외
                recorder.recordProviderFailure(sel.providerId)
                log.debug("provider {} 실패: {}", sel.providerId, lastReason)
            }
        }
        return OrchestrationResult(RequestState.FAILED, failReason = "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다. ($lastReason)")
    }
}
