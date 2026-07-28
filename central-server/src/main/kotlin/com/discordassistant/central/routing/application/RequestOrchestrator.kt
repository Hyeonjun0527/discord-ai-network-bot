package com.discordassistant.central.routing.application

import com.discordassistant.central.knowledge.application.NoWebSearch
import com.discordassistant.central.knowledge.application.WebAugmentation
import com.discordassistant.central.knowledge.application.WebRecency
import com.discordassistant.central.knowledge.application.WebSearchAugmenter
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.RemoteTimeoutException
import com.discordassistant.central.routing.application.port.ALLOW_ALL_BLOCKLIST
import com.discordassistant.central.routing.application.port.ALLOW_ALL_PROVIDER_SAFETY
import com.discordassistant.central.routing.application.port.BlocklistChecker
import com.discordassistant.central.routing.application.port.ProviderProfileProvider
import com.discordassistant.central.routing.application.port.ProviderSafetyChecker
import com.discordassistant.central.routing.application.port.QuotaChecker
import com.discordassistant.central.routing.application.port.RoutingPolicy
import com.discordassistant.central.routing.application.port.UNLIMITED_QUOTA
import com.discordassistant.central.routing.application.port.UsageRecorder
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.routing.domain.model.AttemptFinalState
import com.discordassistant.central.routing.domain.model.OrchestrationResult
import com.discordassistant.central.routing.domain.model.ProviderProfile
import com.discordassistant.central.routing.domain.model.RequestRejectionCode
import com.discordassistant.central.routing.domain.model.RoutingAttemptOutcome
import com.discordassistant.central.routing.domain.model.RoutingCircuitState
import com.discordassistant.central.routing.domain.model.RoutingDecision
import com.discordassistant.central.routing.domain.model.RoutingFailureType
import com.discordassistant.central.routing.domain.model.RoutingLatencyMetrics
import com.discordassistant.central.routing.domain.model.RoutingScoreBreakdown
import com.discordassistant.central.routing.domain.model.defaultDeadlineMillis
import com.discordassistant.central.routing.domain.model.estimatedMaxOutputTokens
import com.discordassistant.central.routing.domain.service.Candidate
import com.discordassistant.central.routing.domain.service.FilterSignal
import com.discordassistant.central.routing.domain.service.IdempotencyDecision
import com.discordassistant.central.routing.domain.service.IdempotencyGuard
import com.discordassistant.central.routing.domain.service.ProviderFilterPipeline
import com.discordassistant.central.routing.domain.service.ProviderRouter
import com.discordassistant.central.routing.domain.service.ProviderRoutingStats
import com.discordassistant.central.routing.domain.service.RequestContext
import com.discordassistant.central.routing.domain.service.RequestMeta
import com.discordassistant.central.routing.domain.service.RequestWeigher
import com.discordassistant.central.routing.domain.service.ReservationResult
import com.discordassistant.central.routing.domain.service.RoutingAttemptLifecycleManager
import com.discordassistant.central.routing.domain.service.RoutingAuditLogger
import com.discordassistant.central.routing.domain.service.RoutingDualVariableManager
import com.discordassistant.central.routing.domain.service.RoutingReservationManager
import com.discordassistant.central.routing.domain.service.WeighDecision
import com.discordassistant.central.routing.domain.service.effectiveConcurrencyLimit
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletionException
import kotlin.math.max
import kotlin.math.min

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
    private val blocklist: BlocklistChecker = ALLOW_ALL_BLOCKLIST,
    private val quota: QuotaChecker = UNLIMITED_QUOTA,
    private val idempotency: IdempotencyGuard = IdempotencyGuard(),
    private val providerSafety: ProviderSafetyChecker = ALLOW_ALL_PROVIDER_SAFETY,
    private val webSearch: WebSearchAugmenter = NoWebSearch,
    private val cloudLlm: CloudLlm = NoCloudLlm,
    private val routingStats: ProviderRoutingStats = ProviderRoutingStats(),
    private val reservationManager: RoutingReservationManager = RoutingReservationManager(pipeline),
    private val duals: RoutingDualVariableManager = RoutingDualVariableManager(),
    private val auditLogger: RoutingAuditLogger = RoutingAuditLogger(),
    private val lifecycle: RoutingAttemptLifecycleManager =
        RoutingAttemptLifecycleManager(reservationManager, routingStats, duals, auditLogger),
) {
    private val log = LoggerFactory.getLogger(RequestOrchestrator::class.java)

    private data class AdmissionDecision(
        val rejection: OrchestrationResult? = null,
        val memberMaxBurden: ModelBurden? = null,
    )

    /**
     * @param dedup 멱등성 중복 차단 적용 여부. 기본 true(유저 요청). /질문 의 무료 클라우드 폴백처럼 같은
     *   프롬프트로 **내부 재시도**할 때는 false 로 호출한다 — 첫 시도에서 이미 중복 검사를 통과했으므로
     *   2차(폴백)를 "동일 요청 중복"으로 막으면 폴백이 영구 실패한다.
     * @param history /질문 멀티턴 단기 기억(채널+유저). 클라우드 직결 경로에서만 Responses input 앞에 붙인다.
     *   앞에 붙는다(시간순). 로컬 에이전트 경로(sendInfer)는 영향 없음(빈 리스트 기본).
     * @param thinking 호출자 호환용 추론 힌트. OpenAI 어댑터는 값과 무관하게 reasoning none을 보낸다.
     */
    fun handle(
        input: AiRequestInput,
        dedup: Boolean = true,
        history: List<CloudTurn> = emptyList(),
        thinking: CloudThinking? = null,
    ): OrchestrationResult {
        // 멱등/한도: 같은 요청은 짧은 윈도우 안에서 최대 5번까지 허용(반복 채팅은 통과), 그 다음부터 막는다.
        // 또한 채널당 하루 상한(기본 50)을 넘으면 막는다. 내부 폴백 재시도는 제외(dedup=false). (#243, 개선)
        if (dedup) {
            val decision = idempotency.begin(input.guildId, input.channelId, input.userId, input.prompt)
            if (decision != IdempotencyDecision.ALLOW) {
                val rej =
                    when (decision) {
                        IdempotencyDecision.DUPLICATE ->
                            rejected(
                                RequestRejectionCode.DUPLICATE_REQUEST,
                                "같은 말을 너무 빠르게 반복했어요. 잠깐 뒤에 다시 해주세요.",
                            )
                        IdempotencyDecision.CHANNEL_DAILY_LIMIT ->
                            rejected(
                                RequestRejectionCode.QUOTA_EXCEEDED,
                                "이 채널은 오늘 AI 사용 한도에 도달했어요. 내일 다시 이용해 주세요.",
                            )
                        IdempotencyDecision.ALLOW -> error("unreachable")
                    }
                recorder.recordRequest(input, rej.state, rej.providerId, rej.failReason, rej.requestId)
                return rej
            }
        }
        val result = route(input, history, thinking)
        recorder.recordRequest(input, result.state, result.providerId, result.failReason, result.requestId, result.effectiveBurden)
        return result
    }

    /** 한 세션 + 프로필 + 런타임 신호(stats/reservation/duals)를 라우팅 [Candidate] 로 조립한다 — route() 에서 추출, 필드 매핑 동작 불변. */
    private fun buildCandidate(
        session: ProviderSession,
        p: ProviderProfile,
        ctx: RequestContext,
        input: AiRequestInput,
    ): Candidate {
        val stats = routingStats.snapshot(session.providerId, ctx.requiredBurden)
        val reservation = reservationManager.snapshot(session.providerId)
        val activeRequests = max(session.activeRequests, reservation.activeReservations)
        val remainingDaily =
            if (session.remainingDailyRequests == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                (session.remainingDailyRequests - reservation.reservedQuotaUnits).coerceAtLeast(0)
            }
        val circuitState =
            if (session.state == com.discordassistant.central.provider.domain.model.ProviderState.UNHEALTHY) {
                RoutingCircuitState.OPEN
            } else {
                RoutingCircuitState.CLOSED
            }
        val heartbeatAgeMillis = if (session.isStale(90)) Long.MAX_VALUE else 0L
        val lambdaSnapshot = duals.snapshot(session.providerId, input.userId, ctx.requiredBurden)
        return Candidate(
            providerId = session.providerId,
            state = session.state,
            supportedBurdens = p.supportedBurdens,
            maxConcurrency = session.capability.maxConcurrency,
            activeRequests = activeRequests,
            remainingDaily = remainingDaily,
            allowedRoleIds = p.allowedRoleIds,
            allowedChannelIds = p.allowedChannelIds,
            maxPromptChars = p.maxPromptChars,
            failureRate = p.failureRate,
            inCooldown = providerSafety.isRoutingProtected(input.guildId, session.providerId),
            recentHandled = stats.recentHandled,
            modelNames = session.capability.models.toSet(),
            qualityTier = p.qualityTier,
            observedSuccessRate = stats.successRate,
            observedTimeoutRate = stats.timeoutRate,
            observedLatencyMillis = stats.latencyMillis,
            observedOutputChars = stats.outputChars,
            observedSampleCount = stats.sampleCount,
            contextLimitTokens = max(1, p.maxPromptChars / 4),
            supportsStreaming = "stream" in session.capability.capabilities || "text" in session.capability.capabilities,
            supportsTools = "tools" in session.capability.capabilities,
            supportsJsonMode = "json" in session.capability.capabilities,
            modelFamilies =
                session.capability.models
                    .map { it.substringBefore(":") }
                    .toSet(),
            privacyCapabilities = p.privacyCapabilities,
            heartbeatAgeMillis = heartbeatAgeMillis,
            circuitState = circuitState,
            trustedConcurrency =
                min(
                    session.capability.maxConcurrency.coerceAtLeast(1),
                    stats.trustedConcurrency.coerceAtLeast(1),
                ),
            centralReservedQuotaUnits = reservation.reservedQuotaUnits,
            estimatedPendingPrefillTokens = reservation.pendingPrefillTokens,
            estimatedPendingDecodeTokens = reservation.pendingDecodeTokens,
            estimatedPendingWorkMillis = reservation.pendingWorkMillis,
            lambdas = lambdaSnapshot,
        )
    }

    private fun route(
        input: AiRequestInput,
        history: List<CloudTurn>,
        thinking: CloudThinking?,
    ): OrchestrationResult {
        val routingRequestId = UUID.randomUUID().toString()
        val arrivalAtNanos = System.nanoTime()
        val admission = admitAtRequestStart(input)
        admission.rejection?.let { return it }
        // 2) 무게 판단 & 필요 수준(권한 상한 반영). 무게 길이는 사용자 실제 입력(weighChars) 우선 —
        //    항상 주입되는 시스템 프롬프트(가드레일·정체성·few-shot)가 부담 수준을 부풀리지 않게 한다.
        val weighChars = input.weighChars ?: input.prompt.length
        val memberMax = requireNotNull(admission.memberMaxBurden) { "admitted request must include max burden snapshot" }
        val weigh =
            weigher.resolve(
                RequestMeta(
                    promptChars = weighChars,
                    attachments = 0,
                    command = input.command,
                    responseMode = input.responseMode,
                ),
                memberMax,
            )
        if (weigh.decision == WeighDecision.REJECT) {
            return rejected(
                RequestRejectionCode.BURDEN_NOT_ALLOWED,
                "이 요청은 ${weigh.requiredBurden} 수준이 필요하지만 현재 권한으로는 사용할 수 없습니다.",
            )
        }
        val ctx =
            RequestContext(
                requiredBurden = weigh.effectiveBurden!!,
                requesterRoleIds = input.roleIds,
                channelId = input.channelId,
                promptChars = weighChars,
                requesterIsAdmin = input.isAdmin,
                preferredModel = input.preferredModel,
                responseMode = input.responseMode,
                requestId = routingRequestId,
                userId = input.userId,
                maxOutputTokens = estimatedMaxOutputTokens(input.responseMode),
                deadlineTtftMillis = defaultDeadlineMillis(weigh.requiredBurden, input.responseMode) / 2,
                deadlineE2eMillis = defaultDeadlineMillis(weigh.requiredBurden, input.responseMode),
                quotaReservationUnits = 1,
            )

        // 검색어에는 정체성·안전 지시를 섞지 않는다. 필수 검색 실패는 사전 지식으로 메우지 못하게 닫는다.
        val searchQuery = input.webSearchQuery?.trim()?.takeIf(String::isNotEmpty) ?: input.prompt
        val wantWeb = input.webSearch || input.webSearchRequired || WebRecency.isTimeSensitive(searchQuery)
        val augmentation =
            if (wantWeb && webSearch.isEnabled()) {
                webSearch.augment(prompt = input.prompt, searchQuery = searchQuery)
            } else {
                WebAugmentation(input.prompt, emptyList())
            }
        val effectivePrompt =
            when {
                input.webSearchRequired && augmentation.sources.isEmpty() ->
                    requiredWebSearchFailurePrompt(input.prompt)
                input.webSearchRequired ->
                    augmentation.prompt + REQUIRED_WEB_SEARCH_RESPONSE_STYLE
                else -> augmentation.prompt
            }

        // 2.7) 무료질문 클라우드 직결(ADR 0006): 정책(차단·일일한도·채널·부담) 검사를 **모두 통과한 뒤**에만
        //       분기하므로 차단 사용자·한도 초과·금지 채널은 클라우드 경로에서도 동일하게 거부된다(정책 우회 0).
        //       클라우드 모델 요청이고 관리자 키가 연결돼 있으면 풀 후보 선택/sendInfer 를 건너뛰고 central 이 직접 추론한다.
        val directCloudModel = input.preferredModel?.takeIf(::isDirectCloudModel)
        if (directCloudModel != null && cloudLlm.isEnabled()) {
            return try {
                // 멀티턴 기억과 호환용 thinking 힌트를 전달한다. OpenAI 어댑터는 reasoning none을 강제한다.
                val cloud =
                    cloudLlm.generate(
                        prompt = effectivePrompt,
                        model = directCloudModel,
                        history = history,
                        thinking = thinking,
                        options =
                            CloudLlmRequestOptions(
                                purpose = CloudLlmPurpose.GENERAL,
                                maxOutputTokens = ctx.maxOutputTokens,
                                maxRetries = 0,
                            ),
                    )
                recorder.recordSuccess(input.guildId, input.userId, CLOUD_PROVIDER_ID, requestId = routingRequestId)
                OrchestrationResult(
                    RequestState.COMPLETED,
                    cloud.text,
                    providerId = null,
                    effectiveBurden = ctx.requiredBurden,
                    requestId = routingRequestId,
                    sources = augmentation.sources,
                )
            } catch (e: CloudLlmException) {
                log.warn("클라우드 LLM 처리 실패: {}", e.message)
                OrchestrationResult(RequestState.FAILED, failReason = e.message ?: "클라우드 AI 처리 실패")
            }
        }

        // 3) 후보 구성 + 필터 + 선택 + 전송(최대 2회: 원 + fallback 1회)
        val excluded = mutableSetOf<Long>()
        var lastReason = NO_PROVIDER_ACTIONABLE_REASON
        repeat(ctx.maxRetryCount + 1) { attempt ->
            val sessions = registry.byGuild(input.guildId).filter { it.providerId !in excluded }
            val profileMap = profiles.profilesFor(input.guildId, sessions.map { it.providerId }) // 후보 프로필 일괄 조회(N+1 제거)
            val candidates =
                sessions
                    .map { session ->
                        val p = profileMap[session.providerId] ?: profiles.profile(input.guildId, session.providerId)
                        buildCandidate(session, p, ctx, input)
                    }
            val outcome = pipeline.filter(candidates, ctx)
            if (outcome.eligible.isEmpty()) {
                auditLogger.recordDecision(
                    requestId = routingRequestId,
                    selectedProviderId = null,
                    candidateProviderIds = candidates.map { it.providerId },
                    infeasibleProviderReasons = outcome.dropped,
                    scoreBreakdowns =
                        outcome.dropped.map { (providerId, reason) ->
                            RoutingScoreBreakdown(
                                providerId = providerId,
                                feasible = false,
                                infeasibleReasons = listOf(reason),
                            )
                        },
                )
                return when {
                    outcome.signal == FilterSignal.PERMISSION_DENIED ->
                        rejected(RequestRejectionCode.POLICY_DENIED, "권한 또는 정책상 처리할 수 없습니다.")
                    outcome.dropped.isNotEmpty() && outcome.dropped.values.all { it == "COOLDOWN" } ->
                        OrchestrationResult(RequestState.FAILED, failReason = PROVIDER_PROTECTION_ACTIONABLE_REASON)
                    else -> OrchestrationResult(RequestState.FAILED, failReason = lastReason)
                }
            }
            val decision = router.decide(outcome.eligible, outcome.dropped, ctx.copy(retryCount = attempt))
            val selectedProviderId =
                when (decision) {
                    is RoutingDecision.ImmediateDispatch -> decision.providerId
                    is RoutingDecision.Queue -> return OrchestrationResult(RequestState.FAILED, failReason = lastReason)
                    is RoutingDecision.Fallback -> return OrchestrationResult(RequestState.FAILED, failReason = decision.reason)
                    is RoutingDecision.Reject -> return OrchestrationResult(RequestState.FAILED, failReason = decision.reason)
                }
            auditLogger.recordDecision(
                requestId = routingRequestId,
                selectedProviderId = selectedProviderId,
                candidateProviderIds = candidates.map { it.providerId },
                infeasibleProviderReasons = outcome.dropped,
                scoreBreakdowns = decision.breakdowns,
                fallbackReason = if (attempt > 0) "retry:$attempt" else null,
            )
            val selectedCandidate = outcome.eligible.single { it.providerId == selectedProviderId }
            val reservation =
                when (val reserve = reservationManager.tryReserve(selectedCandidate, ctx.copy(retryCount = attempt))) {
                    is ReservationResult.Reserved -> {
                        auditLogger.recordReservation(routingRequestId, reserve.reservation)
                        reserve.reservation
                    }
                    is ReservationResult.Rejected -> {
                        auditLogger.recordReservationRejected(routingRequestId, selectedProviderId, reserve.reason)
                        excluded.add(selectedProviderId)
                        lastReason = reserve.reason
                        return@repeat
                    }
                }
            val session = registry.byProvider(input.guildId, selectedProviderId)
            if (session == null) {
                reservationManager.finalize(reservation.reservationId, AttemptFinalState.REJECTED_BY_PROVIDER)
                excluded.add(selectedProviderId)
                return@repeat
            }
            if (attempt > 0) log.info("fallback 시도 → provider {}", selectedProviderId)
            val dispatchAtNanos = System.nanoTime()
            val routingAttempt = lifecycle.startAttempt(ctx.copy(retryCount = attempt), reservation, dispatchAtNanos)
            try {
                val result =
                    session
                        .sendInfer(
                            prompt = effectivePrompt,
                            model = input.preferredModel,
                            options = responseModeOptions(input.responseMode),
                        ).get()
                val completedAtNanos = System.nanoTime()
                val latency =
                    RoutingLatencyMetrics(
                        arrivalAtNanos = arrivalAtNanos,
                        dispatchAtNanos = dispatchAtNanos,
                        firstTokenAtNanos = completedAtNanos,
                        completedAtNanos = completedAtNanos,
                        generatedTokens = max(1, result.usage.completionTokens.takeIf { it > 0 } ?: result.text.length / 4),
                    )
                val sloMet =
                    latency.ttftMillis <= ctx.deadlineTtftMillis &&
                        latency.averageTbtMillis <= ctx.deadlineTbtMillis &&
                        latency.e2eMillis <= ctx.deadlineE2eMillis
                val actualInputTokens = result.usage.promptTokens.takeIf { it > 0 } ?: ctx.promptTokens
                val actualOutputTokens = result.usage.completionTokens.takeIf { it > 0 } ?: max(1, result.text.length / 4)
                val outcomeForAttempt =
                    RoutingAttemptOutcome(
                        finalState = AttemptFinalState.SUCCESS,
                        latency = latency,
                        actualInputTokens = actualInputTokens,
                        actualOutputTokens = actualOutputTokens,
                        qualityMet = true,
                        sloMet = sloMet,
                    )
                lifecycle.finalizeAttempt(
                    routingAttempt,
                    outcomeForAttempt,
                    quotaPressure = quotaPressure(selectedCandidate),
                    providerBurdenPressure = providerBurdenPressure(selectedCandidate),
                )
                recorder.recordSuccess(input.guildId, input.userId, selectedProviderId, requestId = routingRequestId)
                return OrchestrationResult(
                    RequestState.COMPLETED,
                    result.text,
                    selectedProviderId,
                    effectiveBurden = ctx.requiredBurden,
                    requestId = routingRequestId,
                    sources = augmentation.sources,
                )
            } catch (e: Exception) {
                lastReason = e.cause?.message ?: e.message ?: "처리 실패"
                excluded.add(selectedProviderId)
                auditLogger.recordFallback(routingRequestId, selectedProviderId, lastReason)
                val completedAtNanos = System.nanoTime()
                val timeout = e.isTimeoutFailure()
                val outcomeForAttempt =
                    RoutingAttemptOutcome(
                        finalState = if (timeout) AttemptFinalState.TIMEOUT else AttemptFinalState.FAILED,
                        failureType = if (timeout) RoutingFailureType.END_TO_END_TIMEOUT else RoutingFailureType.MODEL_ERROR,
                        latency =
                            RoutingLatencyMetrics(
                                arrivalAtNanos = arrivalAtNanos,
                                dispatchAtNanos = dispatchAtNanos,
                                firstTokenAtNanos = completedAtNanos,
                                completedAtNanos = completedAtNanos,
                                generatedTokens = 0,
                            ),
                        actualInputTokens = ctx.promptTokens,
                        actualOutputTokens = 0,
                        qualityMet = false,
                        sloMet = false,
                    )
                lifecycle.finalizeAttempt(
                    routingAttempt,
                    outcomeForAttempt,
                    quotaPressure = quotaPressure(selectedCandidate),
                    providerBurdenPressure = providerBurdenPressure(selectedCandidate),
                )
                recorder.recordProviderFailure(selectedProviderId)
                log.debug("provider {} 실패: {}", selectedProviderId, lastReason)
            }
        }
        return OrchestrationResult(RequestState.FAILED, failReason = noProviderActionableReason(lastReason))
    }

    private fun requiredWebSearchFailurePrompt(prompt: String): String =
        "$prompt\n\n[실시간 확인]\n" +
            "웹 검색 결과를 얻지 못했다. 기억으로 대신하지 말고 지금은 확인할 수 없다고 자연스럽게 답한다."

    private fun isDirectCloudModel(model: String?): Boolean {
        val normalized = model?.trim()?.lowercase() ?: return false
        return normalized.startsWith("gpt-")
    }

    /**
     * 요청 admission 정책은 시작 시점에 1회 스냅샷으로 판정한다.
     *
     * 채널 allow-list/쿼터/차단 정책이 provider 실행 중 바뀌어도 이미 admission 을 통과해 전송된 요청은 같은
     * requestId 로 종결한다. 운영자 정책 변경은 새 요청부터 적용된다. 중간 재검사로 완료 직전 답변을 폐기하면
     * 사용자가 이미 접수된 요청의 결과를 잃고, usage/request log 상태머신도 "전송됨 → 정책거절"로 되돌아가
     * 최소 놀람과 종단 상태 불변성을 깨기 때문이다.
     */
    private fun admitAtRequestStart(input: AiRequestInput): AdmissionDecision {
        if (blocklist.isBlocked(input.guildId, input.userId)) {
            return AdmissionDecision(rejected(RequestRejectionCode.BLOCKED_USER, "차단된 사용자입니다."))
        }
        if (quota.exceededQuota(input.guildId, input.userId, input.roleIds)) {
            return AdmissionDecision(rejected(RequestRejectionCode.QUOTA_EXCEEDED, "오늘 사용 한도를 초과했습니다. 내일 다시 시도해 주세요."))
        }
        if (!policy.isChannelAllowed(input.guildId, input.channelId)) {
            return AdmissionDecision(rejected(RequestRejectionCode.CHANNEL_NOT_ALLOWED, "이 채널에서는 LLM 을 사용할 수 없습니다."))
        }
        return AdmissionDecision(memberMaxBurden = policy.maxAllowedBurden(input.guildId, input.roleIds))
    }

    private fun rejected(
        code: RequestRejectionCode,
        message: String,
    ): OrchestrationResult =
        OrchestrationResult(
            state = RequestState.REJECTED,
            failReason = message,
            rejectionCode = code,
        )

    companion object {
        private const val REQUIRED_WEB_SEARCH_RESPONSE_STYLE =
            "\n\n[대화 방식]\n검색 과정이나 출처 번호를 말하지 말고 확인한 내용으로 자연스럽게 답한다."

        // 무료질문 클라우드 직결(ADR 0006)의 사용량 기록용 합성 providerId. 실제 풀 프로바이더(Discord
        // user id, 양수)와 절대 겹치지 않게 음수 sentinel 을 쓴다 — 통계상 "central 직결"을 구분.
        const val CLOUD_PROVIDER_ID = -1L

        const val PROVIDER_PROTECTION_ACTIONABLE_REASON =
            "지금은 참여 PC를 보호하기 위해 답변 요청을 줄이고 있어요.\n\n" +
                "과부하 또는 보호 상태인 Provider는 자동으로 제외됩니다.\n" +
                "• 잠시 후 다시 질문하거나 `절약`/`빠른` 모드로 시도해 주세요.\n" +
                "• Provider라면 `/내상태`에서 수신 상태와 PC 부하를 확인해 주세요.\n" +
                "• 관리자는 AI 네트워크 대시보드의 과부하 알림을 확인해 주세요."

        const val NO_PROVIDER_ACTIONABLE_REASON =
            "지금은 답변을 처리할 온라인 AI Provider가 없습니다.\n\n" +
                "무료 클라우드 AI도 아직 연결되어 있지 않아요. 다음 중 하나가 필요해요.\n" +
                "• 관리자가 무료 클라우드 AI 키를 연결하면 `/질문`이 자동으로 무료 클라우드로 답해드려요.\n" +
                "• 내 컴퓨터의 AI로 함께 도와주기: `/메뉴` → `함께 도와주기` 또는 `/프로바이더참여`\n" +
                "• 이미 참여했다면 에이전트 터미널이 켜져 있는지 확인하고 `/내상태`를 눌러보세요.\n" +
                "• 관리자는 `/프로바이더목록`으로 온라인 Provider가 있는지 확인할 수 있어요.\n\n" +
                "Provider가 연결되면 다시 질문해주세요."

        fun noProviderActionableReason(lastReason: String): String =
            if (lastReason == NO_PROVIDER_ACTIONABLE_REASON) {
                NO_PROVIDER_ACTIONABLE_REASON
            } else {
                "$NO_PROVIDER_ACTIONABLE_REASON\n\n세부 원인: $lastReason"
            }

        fun responseModeOptions(responseMode: String): Map<String, Any?> =
            when (responseMode.lowercase()) {
                "fast" -> mapOf("num_predict" to 512, "temperature" to 0.3)
                "deep" -> mapOf("num_predict" to 2048, "temperature" to 0.5)
                "saving" -> mapOf("num_predict" to 384, "temperature" to 0.2)
                else -> emptyMap()
            }

        fun elapsedMillisSince(startedAtNanos: Long): Long = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(1L)

        fun Throwable.isTimeoutFailure(): Boolean {
            var cursor: Throwable? = this
            while (cursor != null) {
                if (cursor is RemoteTimeoutException) return true
                cursor = if (cursor is CompletionException) cursor.cause else cursor.cause
            }
            return false
        }

        fun quotaPressure(candidate: Candidate): Double =
            if (candidate.remainingDaily == Int.MAX_VALUE) {
                0.0
            } else {
                (candidate.centralReservedQuotaUnits.toDouble() / candidate.remainingDaily.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            }

        fun providerBurdenPressure(candidate: Candidate): Double =
            candidate.activeRequests.toDouble() / candidate.effectiveConcurrencyLimit().coerceAtLeast(1).toDouble()
    }
}
