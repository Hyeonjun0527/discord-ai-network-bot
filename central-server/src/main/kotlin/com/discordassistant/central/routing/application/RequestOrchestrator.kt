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
    private val routingStats: ProviderRoutingStats = ProviderRoutingStats(),
    private val reservationManager: RoutingReservationManager = RoutingReservationManager(pipeline),
    private val duals: RoutingDualVariableManager = RoutingDualVariableManager(),
    private val auditLogger: RoutingAuditLogger = RoutingAuditLogger(),
    private val lifecycle: RoutingAttemptLifecycleManager =
        RoutingAttemptLifecycleManager(reservationManager, routingStats, duals, auditLogger),
) {
    private val log = LoggerFactory.getLogger(RequestOrchestrator::class.java)

    fun handle(input: AiRequestInput): OrchestrationResult {
        // 멱등성: 짧은 윈도우 내 동일 요청 중복은 라우팅 없이 막는다(#243).
        if (!idempotency.tryBegin(input.guildId, input.userId, input.prompt)) {
            val dup = OrchestrationResult(RequestState.REJECTED, failReason = "동일한 요청이 방금 접수되었습니다. 잠시 후 다시 시도해 주세요.")
            recorder.recordRequest(input, dup.state, dup.providerId, dup.failReason, dup.requestId)
            return dup
        }
        val result = route(input)
        recorder.recordRequest(input, result.state, result.providerId, result.failReason, result.requestId)
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

    private fun route(input: AiRequestInput): OrchestrationResult {
        val routingRequestId = UUID.randomUUID().toString()
        val arrivalAtNanos = System.nanoTime()
        // 0) 차단 사용자 / 일일 쿼터
        if (blocklist.isBlocked(input.guildId, input.userId)) {
            return OrchestrationResult(RequestState.REJECTED, failReason = "차단된 사용자입니다.")
        }
        if (quota.exceededQuota(input.guildId, input.userId, input.roleIds)) {
            return OrchestrationResult(RequestState.REJECTED, failReason = "오늘 사용 한도를 초과했습니다. 내일 다시 시도해 주세요.")
        }
        // 1) 정책 확인(채널)
        if (!policy.isChannelAllowed(input.guildId, input.channelId)) {
            return OrchestrationResult(RequestState.REJECTED, failReason = "이 채널에서는 LLM 을 사용할 수 없습니다.")
        }
        // 2) 무게 판단 & 필요 수준(권한 상한 반영). 무게 길이는 사용자 실제 입력(weighChars) 우선 —
        //    항상 주입되는 시스템 프롬프트(가드레일·정체성·few-shot)가 부담 수준을 부풀리지 않게 한다.
        val weighChars = input.weighChars ?: input.prompt.length
        val memberMax = policy.maxAllowedBurden(input.guildId, input.roleIds)
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
            return OrchestrationResult(
                RequestState.REJECTED,
                failReason = "이 요청은 ${weigh.requiredBurden} 수준이 필요하지만 현재 권한으로는 사용할 수 없습니다.",
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

        // 2.5) 웹검색 증강: 로컬 모델이 웹을 못 보므로 서버가 검색해 프롬프트에 주입한다.
        //      web:true 명시 OR **시간 민감 질의(최신/연도/뉴스 등)면 자동**으로 검색한다(유저가 web 옵션을
        //      안 줘도 필요할 때 최신 정보를 끌어옴). 비활성/미설정/실패면 원본 그대로(루프 밖 1회 — fallback 시 재검색 안함).
        val wantWeb = input.webSearch || WebRecency.isTimeSensitive(input.prompt)
        val augmentation =
            if (wantWeb && webSearch.isEnabled()) webSearch.augment(input.prompt) else WebAugmentation(input.prompt, emptyList())
        val effectivePrompt = augmentation.prompt

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
                        OrchestrationResult(RequestState.REJECTED, failReason = "권한 또는 정책상 처리할 수 없습니다.")
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

    companion object {
        const val PROVIDER_PROTECTION_ACTIONABLE_REASON =
            "지금은 참여 PC를 보호하기 위해 답변 요청을 줄이고 있어요.\n\n" +
                "과부하 또는 보호 상태인 Provider는 자동으로 제외됩니다.\n" +
                "• 잠시 후 다시 질문하거나 `절약`/`빠른` 모드로 시도해 주세요.\n" +
                "• Provider라면 `/내상태`에서 수신 상태와 PC 부하를 확인해 주세요.\n" +
                "• 관리자는 AI 네트워크 대시보드의 과부하 알림을 확인해 주세요."

        const val NO_PROVIDER_ACTIONABLE_REASON =
            "지금은 답변을 처리할 온라인 AI Provider가 없습니다.\n\n" +
                "다음 중 하나를 해주세요.\n" +
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
