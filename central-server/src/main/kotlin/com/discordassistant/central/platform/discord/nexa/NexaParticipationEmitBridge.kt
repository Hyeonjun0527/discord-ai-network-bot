package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.ParticipationActionRouter
import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationCommand
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.conversation.application.port.out.RawContextStorePort
import com.discordassistant.central.conversation.application.scene.ConversationObservation
import com.discordassistant.central.conversation.application.scene.ConversationSceneIngress
import com.discordassistant.central.conversation.application.scene.InMemoryConversationSceneIngress
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRetentionPolicy
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import com.discordassistant.central.participation.application.DecisionProvenance
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.application.context.JudgeContextWindowBuilder
import com.discordassistant.central.participation.application.context.NiaJudgeContextAssembler
import com.discordassistant.central.participation.application.context.NiaJudgeContextInput
import com.discordassistant.central.participation.application.debug.ParticipationGateTrace
import com.discordassistant.central.participation.application.debug.ParticipationGateTraceFeatures
import com.discordassistant.central.participation.application.debug.ParticipationGateTraceStore
import com.discordassistant.central.participation.application.debug.ParticipationTraceMessage
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.feature.MemoryObservation
import com.discordassistant.central.participation.application.feature.RelationshipObservation
import com.discordassistant.central.participation.application.fewshot.NiaFewShotService
import com.discordassistant.central.participation.application.fewshot.NiaFewShotSpeechPromptRenderer
import com.discordassistant.central.participation.application.judge.JudgeCommonGroundState
import com.discordassistant.central.participation.application.judge.JudgeDecisionConstraints
import com.discordassistant.central.participation.application.judge.JudgeFewShotBadAlternativePayload
import com.discordassistant.central.participation.application.judge.JudgeFewShotExamplePayload
import com.discordassistant.central.participation.application.judge.JudgeFewShotRawMessagePayload
import com.discordassistant.central.participation.application.judge.JudgeFewShotSetPayload
import com.discordassistant.central.participation.application.judge.JudgeGroundingNeed
import com.discordassistant.central.participation.application.judge.JudgeIntentHypothesisState
import com.discordassistant.central.participation.application.judge.JudgeRecentNiaActionState
import com.discordassistant.central.participation.application.judge.JudgeRecentOutcomeState
import com.discordassistant.central.participation.application.judge.JudgeResponseObligation
import com.discordassistant.central.participation.application.judge.JudgeSocialBeliefState
import com.discordassistant.central.participation.application.judge.JudgeSpeechIntent
import com.discordassistant.central.participation.application.judge.NiaJudgePromptAssembler
import com.discordassistant.central.participation.application.judge.SingleJudgeDecision
import com.discordassistant.central.participation.application.judge.SingleJudgeDecisionRequest
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneBuildResult
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneObservation
import com.discordassistant.central.participation.application.judge.SingleJudgeSceneSnapshotBuilder
import com.discordassistant.central.participation.application.judge.SingleParticipationJudgePort
import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.application.shadow.NiaJudgeShadowResult
import com.discordassistant.central.participation.application.shadow.NiaJudgeShadowService
import com.discordassistant.central.participation.domain.model.action.PendingActionId
import com.discordassistant.central.participation.domain.model.action.ReactionCode
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDelay
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotLookupScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.service.AttentionGateConstants
import com.discordassistant.central.participation.domain.service.BanterSafetyContext
import com.discordassistant.central.participation.domain.service.ChannelAttentionGate
import com.discordassistant.central.participation.domain.service.CoreInterventionRules
import com.discordassistant.central.quota.application.RateLimitStore
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.socialmemory.application.port.out.PendingIntentStore
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.intent.IntentActivation
import com.discordassistant.central.socialmemory.domain.model.intent.IntentUrgency
import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import com.discordassistant.central.socialmemory.domain.model.intent.SocialAct
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialpolicy.application.port.out.InteractionOutcomePort
import com.discordassistant.central.socialpolicy.application.port.out.SceneBeliefStatePort
import com.discordassistant.central.socialpolicy.application.port.out.SceneObservation
import com.discordassistant.central.socialpolicy.domain.model.BeliefStatus
import com.discordassistant.central.socialpolicy.domain.model.CommonGroundBelief
import com.discordassistant.central.socialpolicy.domain.model.IntentHypothesisBelief
import com.discordassistant.central.socialpolicy.domain.model.InteractionEvidenceRef
import com.discordassistant.central.socialpolicy.domain.model.ObservedInteractionOutcome
import com.discordassistant.central.socialpolicy.domain.model.ObservedOutcomeCode
import com.discordassistant.central.socialpolicy.domain.model.RecentInteractionOutcomeBelief
import com.discordassistant.central.socialpolicy.domain.model.RecentNiaActionBelief
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefDelta
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefState
import com.discordassistant.central.speech.application.generation.GenerationBudget
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechGroundingNeed
import com.discordassistant.central.speech.domain.model.SpeechResponseObligation
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import com.discordassistant.central.participation.application.judge.SpeechDeliveryMode as JudgeSpeechDeliveryMode
import com.discordassistant.central.participation.domain.model.action.SocialAct as ParticipationSocialAct
import com.discordassistant.central.participation.domain.model.action.SpeechDeliveryMode as ActionSpeechDeliveryMode

/**
 * NEXA participation **자발 발화 wiring**(NEXA participation-activation-plan 단계 1, platform/discord 어댑터).
 *
 * "AI 채팅 채널"(NEXA participation flag 가 활성인 (guild, channel))에서 받은 메시지에 대해, 니아가 **스스로**
 * 발화/리액션/침묵을 판단하고 잠정 SPEAK 면 단일 보안 seam [NexaSpeechEmitService.emit] 를 호출한다. 기존 채널 무조건
 * 답변(autoRespond)과 별개의 participation 경로다. participation flag가 비활성일 때만 legacy autoRespond가 응답을 소유한다.
 *
 * **안전(단계 1 핵심)**:
 *  1. **flag 가드(기본 OFF)**: [NexaParticipationFlagService.isNexaActive] 가 true 일 때만 평가/emit 한다. 기본값은
 *     OFF([com.discordassistant.central.participation.domain.model.shadow.ShadowMode.DEFAULT]) 이라, flag 를 명시
 *     승인하지 않은 모든 (guild, channel)에서는 **아무 것도 하지 않는다**(기존 동작 100% 보존).
 *  2. **전송 0(SHADOW_PREDICT)**: emit 가 행동을 **예약**해도, 실제 Discord 전송은 actionruntime 전송 경계
 *     ([com.discordassistant.central.actionruntime.application.ShadowOutboundDispatcher])가 ShadowMode 로 hard
 *     block 한다 — SHADOW_PREDICT 는 `allowsRealSend=false` 라 전송 port 가 **호출되지 않는다**. 즉 wiring 을 켜고
 *     SHADOW_PREDICT 로 두면 평가·기록은 되지만 사용자에게 메시지가 나가지 않는다. CANARY/LIVE 승격은 별도 단계.
 *  3. **실행 permit 안전망**: 각 실제 Discord SEND bubble/REACT 호출 직전에 채널별/전역 원자적 permit을 소비한다.
 *     후보 생성·예약과 실행 quota를 분리해 실행되지 않은 후보에는 실행량을 차감하지 않고, 다중 인스턴스에서도 hard cap을 지킨다.
 *  4. **보안 enforcement 내장**: emit → [com.discordassistant.central.speech.application.NexaSpeechPipelineService]
 *     경로가 ConsentGate(2단계 동의)·비밀 유출/전송 형식 검증·LIVE 모델 검증을 **강제**한다. 이
 *     브리지는 emit 를 호출만 하며 **우회 경로를 만들지 않는다**.
 *  5. **fail-closed**: FINAL judge가 실제 전송을 소유한 채널의 평가/emit 실패는 흡수하고 로그를 남기며 legacy 응답으로
 *     우회하지 않는다. shadow 채널은 관찰만 하고 기존 응답 계약을 보존한다.
 *
 * 순수성 경계: platform 어댑터 — participation/speech/actionruntime 의 공개 application 클래스·도메인 값 객체만
 * 조립한다(여러 도메인 application 을 묶는 것은 adapter 의 허용 책임). JDA 전송은 참조하지 않는다.
 */
@Component
class NexaParticipationEmitBridge(
    private val flags: NexaParticipationFlagService,
    // 같은 타입(ParticipationPolicyPort)의 다른 정책 bean(legacyAutoRespond 등)과 구분 — participation 평가 전용
    // baseline 정책을 명시 선택한다(BaselineParticipationPolicyConfig.PARTICIPATION_EVAL_POLICY_BEAN).
    @param:Qualifier("participationEvalPolicy") private val policy: ParticipationPolicyPort,
    private val emit: NexaSpeechEmitService,
    // 이전 생성 전 rate-limit 생성자 계약과의 호환용 의존성. 실제 SEND/REACT 제한은 emit의 ExecutionPermitPort가 소유한다.
    @Suppress("UNUSED_PARAMETER") rateLimitStore: RateLimitStore,
    @param:Value("\${central.nexa.participation.rate-limit.per-channel-per-min:6}") private val perChannelPerMin: Int,
    @param:Value("\${central.nexa.participation.rate-limit.global-per-min:30}") private val globalPerMin: Int,
    @param:Value("\${central.nexa.participation.speech.raw-context.max-chars:12000}")
    private val speechRawContextMaxChars: Int = 12_000,
    @param:Value("\${central.nexa.judge.mode:final}") private val judgeModeName: String = NexaJudgeMode.FINAL.wireName,
    private val rawContextStore: RawContextStorePort? = null,
    private val decisionLog: ParticipationDecisionLogPort? = null,
    private val traceStore: ParticipationGateTraceStore = ParticipationGateTraceStore(),
    private val judgeShadowService: NiaJudgeShadowService? = null,
    private val singleJudge: SingleParticipationJudgePort? = null,
    private val fewShotService: NiaFewShotService? = null,
    private val actionRouter: ParticipationActionRouter? = null,
    private val turnGenerations: NiaTurnGenerationTracker = NiaTurnGenerationTracker(),
    private val sceneBeliefState: SceneBeliefStatePort? = null,
    private val interactionOutcomes: InteractionOutcomePort? = null,
    private val pendingIntents: PendingIntentStore? = null,
    private val conversationSceneIngress: ConversationSceneIngress = InMemoryConversationSceneIngress(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(NexaParticipationEmitBridge::class.java)
    private val judgeMode = NexaJudgeMode.parse(judgeModeName)
    private val speechRawContextWindowBuilder =
        JudgeContextWindowBuilder(speechRawContextMaxChars, niaAuthorPseudonyms = setOf(NIA_RAW_CONTEXT_AUTHOR_PSEUDONYM))
    private val judgeContextAssembler =
        NiaJudgeContextAssembler(
            JudgeContextWindowBuilder(
                RawContextRetentionPolicy.DEFAULT_MAX_RAW_CHARS,
                niaAuthorPseudonyms = setOf(NIA_RAW_CONTEXT_AUTHOR_PSEUDONYM),
            ),
        )

    /**
     * 채널별 [ChannelAttentionGate.ChannelAttentionState] (pingpong 앵커·recent_gaps median·typing 유예). emit 경로가
     * 메시지마다 동기 평가하므로 능동 타이머 없이 이 상태로 타이밍(pingpong wake·min_gap debounce·dynamic_idle)을 낸다.
     * 채널 수만큼만 자라는 경량 상태(가변 var 필드). 동시성: ConcurrentHashMap + 채널 상태 단위 동기화로 보호한다.
     */
    private val attentionStates =
        java.util.concurrent.ConcurrentHashMap<String, ChannelAttentionGate.ChannelAttentionState>()
    private val lastHumanSignals = java.util.concurrent.ConcurrentHashMap<String, ParticipationMessageSignal>()

    /**
     * [signal] (raw Discord 메시지 신호)에 대해 NEXA participation 평가를 돌리고, 정책이 낸 분포가 SPEAK 로 접히면
     * [NexaSpeechEmitService.emit] 를 호출한다. flag OFF 면 no-op이고, 활성 participation 채널의 실패는 흡수하되
     * 비발화로 fail-closed한다. 무엇이 일어났는지([ParticipationEmitOutcome])를 돌려준다(테스트·관찰용).
     */
    fun onMessage(signal: ParticipationMessageSignal): ParticipationEmitOutcome = onMessageTurn(signal).outcome

    /**
     * Captures the effective rollout mode exactly once and returns both the decision and ownership derived from that
     * same snapshot. The Discord adapter must use this result rather than re-reading mutable rollout state.
     */
    fun onMessageTurn(signal: ParticipationMessageSignal): ParticipationTurnOutcome {
        val mode = flags.effectiveMode(guildId = signal.guildId, channelId = signal.channelId)
        if (!mode.evaluatesPolicy) {
            return turnOutcome(
                mode,
                recordTrace(
                    signal = signal,
                    mode = mode,
                    outcome = ParticipationEmitOutcome.Inactive,
                ),
            )
        }
        if (signal.sourceType != ParticipationMessageSourceType.HUMAN) {
            return turnOutcome(
                mode,
                recordTrace(
                    signal = signal,
                    mode = mode,
                    outcome = ParticipationEmitOutcome.RuleSilent("RULE_NON_HUMAN_SOURCE"),
                ),
            )
        }
        if (!signal.reevaluationWake && signal.isParticipationCommandLike()) {
            return turnOutcome(
                mode,
                recordTrace(
                    signal = signal,
                    mode = mode,
                    outcome = ParticipationEmitOutcome.RuleSilent("RULE_COMMAND_LIKE"),
                ),
            )
        }
        if (!signal.reevaluationWake && !captureRawContext(signal)) {
            return turnOutcome(
                mode,
                recordTrace(signal = signal, mode = mode, outcome = ParticipationEmitOutcome.Failed),
            )
        }
        if (!turnGenerations.isLatest(signal.channelId, signal.turnGeneration)) {
            return turnOutcome(
                mode,
                recordTrace(
                    signal = signal,
                    mode = mode,
                    outcome = ParticipationEmitOutcome.Superseded(NiaTurnSupersessionStage.BEFORE_JUDGE),
                ),
            )
        }
        val effectiveSignal = synchronizeScene(signal)
        if (effectiveSignal == null) {
            log.warn("NEXA 장면 projection 갱신 실패(channel={}) — 이번 턴 fail-closed", signal.channelId)
            return turnOutcome(mode, recordTrace(signal, mode, ParticipationEmitOutcome.Failed))
        }
        if (!effectiveSignal.reevaluationWake) rememberLastHumanSignal(effectiveSignal)
        if (mode.allowsRealSend && judgeMode != NexaJudgeMode.FINAL) {
            // Rollout guard, not social-policy logic: a non-final judge mode may collect comparison data in shadow,
            // but it must never use core/baseline or legacy behavior to emit in CANARY/LIVE.
            log.error(
                "NEXA participation real-send lane requires final judge mode(channel={}, mode={}); fail-closing this turn",
                effectiveSignal.channelId,
                judgeMode.wireName,
            )
            return turnOutcome(
                mode,
                recordTrace(
                    signal = effectiveSignal,
                    mode = mode,
                    outcome = ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE),
                ),
            )
        }
        val outcome =
            try {
                evaluateAndEmit(effectiveSignal, originRolloutMode = mode)
            } catch (e: Exception) {
                // 활성 participation 채널은 judge/안전 경계를 우회하지 않는다. 실패는 로그 후 비발화로 fail-closed한다.
                log.warn(
                    "NIA_TURN_FAILED trace={} stage=EVALUATE_AND_EMIT reason={}",
                    diagnosticTraceOf(effectiveSignal),
                    e.message,
                )
                ParticipationEmitOutcome.Failed
            }
        return turnOutcome(mode, recordTrace(signal = effectiveSignal, mode = mode, outcome = outcome))
    }

    /** WAIT outbox가 마지막 사람 장면을 중복 저장하지 않고 최신 context version으로 다시 판단한다. */
    fun onWaitReevaluation(command: WaitReevaluationCommand): Boolean {
        if (!Instant.now(clock).isBefore(command.expiresAt) || command.wakeAttempt > MAX_WAIT_REEVALUATIONS) return true
        val last = command.routingChannelId?.let(lastHumanSignals::get) ?: restoreWaitSignal(command)
        if (last == null) {
            log.warn("NEXA WAIT 장면 복원 실패(child={}) — 만료 전 다음 tick에서 재시도", command.childDecisionId)
            return false
        }
        if (guildPseudonym(last.guildId) != command.guildPseudonym) return false
        val now = clock.millis()
        val signal =
            last.copy(
                pendingActionIds = (last.pendingActionIds + command.waitActionIdentity).distinct(),
                silenceMillis = (now - last.tsMs).coerceAtLeast(0),
                burstIncomplete = false,
                contextVersion = command.observedContextVersion,
                sceneSeq = maxOf(last.sceneSeq, command.observedContextVersion),
                seed = command.childDecisionId.hashCode().toLong() and Long.MAX_VALUE,
                turnGeneration = last.turnGeneration,
                tsMs = now,
                reevaluationWake = true,
                decisionIdOverride = command.childDecisionId,
                waitAttempt = command.wakeAttempt,
                waitExpiresAt = command.expiresAt,
            )
        return onMessageTurn(signal).outcome !== ParticipationEmitOutcome.Failed
    }

    private fun synchronizeScene(signal: ParticipationMessageSignal): ParticipationMessageSignal? {
        val conversationSnapshot =
            if (signal.reevaluationWake) {
                conversationSceneIngress.current(signal.channelId)
            } else {
                conversationSceneIngress.observe(
                    ConversationObservation(
                        guildId = signal.guildId,
                        channelId = signal.channelId,
                        observationRef = "discord_message:${messagePseudonym(signal.guildId, signal.messageId)}",
                        observedAt = recordedAtOf(signal),
                    ),
                )
            } ?: return null
        val versionedSignal =
            signal.copy(
                sceneSeq = conversationSnapshot.sceneSeq,
                contextVersion = conversationSnapshot.contextVersion,
            )
        val store = sceneBeliefState ?: return versionedSignal
        val guild = guildPseudonym(versionedSignal.guildId)
        val focusKey = focusThreadKey(versionedSignal, guild)
        val observedState =
            if (versionedSignal.reevaluationWake) {
                store.find(focusKey)
            } else {
                store.observe(
                    SceneObservation(
                        guildPseudonym = guild,
                        channelId = channelPseudonym(versionedSignal.guildId, versionedSignal.channelId),
                        focusThreadKey = focusKey,
                        sceneSeq = conversationSnapshot.sceneSeq,
                        contextVersion = conversationSnapshot.contextVersion,
                        evidenceRef = "raw_context_message:${messagePseudonym(versionedSignal.guildId, versionedSignal.messageId)}",
                        observedAt = recordedAtOf(versionedSignal),
                    ),
                )
            } ?: return null
        val state =
            if (!versionedSignal.reevaluationWake) {
                observePriorActionOutcome(versionedSignal, focusKey)?.let { outcome ->
                    store.recordOutcome(
                        focusKey,
                        RecentInteractionOutcomeBelief(
                            actionId = outcome.actionId,
                            code = outcome.code.name.lowercase(),
                            evidenceRef = outcome.evidenceRef,
                            occurredAt = outcome.observedAt,
                        ),
                    )
                } ?: observedState
            } else {
                observedState
            }
        val openCommitment = pendingIntents?.findActive(focusKey, Instant.now(clock))?.isNotEmpty()
        return versionedSignal.copy(
            sceneSeq = conversationSnapshot.sceneSeq,
            contextVersion = conversationSnapshot.contextVersion,
            socialBeliefState = state.toJudgeState(),
            memoryObservation =
                openCommitment?.let { isActive ->
                    versionedSignal.memoryObservation?.copy(pendingIntentActive = isActive)
                        ?: MemoryObservation(false, 0.0, 0.0, isActive)
                } ?: versionedSignal.memoryObservation,
        )
    }

    private fun observePriorActionOutcome(
        signal: ParticipationMessageSignal,
        focusKey: String,
    ): ObservedInteractionOutcome? =
        interactionOutcomes?.observeLatest(
            focusThreadKey = focusKey,
            code = explicitOutcomeCode(signal.rawText),
            evidenceRef = "raw_context_message:${messagePseudonym(signal.guildId, signal.messageId)}",
            replyToMessageRef = signal.replyToMessageId?.toString()?.let(InteractionEvidenceRef::discordMessage),
            explicitActionId = signal.pendingActionIds.singleOrNull(),
            observedAt = recordedAtOf(signal),
        )

    private fun explicitOutcomeCode(rawText: String): ObservedOutcomeCode {
        val normalized = rawText.lowercase()
        return when {
            listOf("같은 말", "또 똑같", "반복").any(normalized::contains) -> ObservedOutcomeCode.REPETITION_COMPLAINT
            listOf("한다며", "해준다며", "준비한다며", "약속").any(normalized::contains) -> ObservedOutcomeCode.PROMISE_COMPLAINT
            listOf("고마워", "좋아", "맞아", "ㅋㅋ").any(normalized::contains) -> ObservedOutcomeCode.POSITIVE_FEEDBACK
            listOf("아니", "틀렸", "왜 그래", "그만").any(normalized::contains) -> ObservedOutcomeCode.NEGATIVE_FEEDBACK
            else -> ObservedOutcomeCode.HUMAN_FOLLOW_UP
        }
    }

    private fun rememberLastHumanSignal(signal: ParticipationMessageSignal) {
        lastHumanSignals[signal.channelId.toString()] = signal
        if (lastHumanSignals.size > MAX_CACHED_CHANNEL_SIGNALS) {
            lastHumanSignals.keys.firstOrNull()?.let(lastHumanSignals::remove)
        }
    }

    private fun restoreWaitSignal(command: WaitReevaluationCommand): ParticipationMessageSignal? {
        val guildId = command.routingGuildId?.toLongOrNull() ?: return null
        val userId = command.routingUserId?.toLongOrNull() ?: return null
        val channelId = command.routingChannelId?.toLongOrNull() ?: return null
        if (guildPseudonym(guildId) != command.guildPseudonym || channelPseudonym(guildId, channelId) != command.channelId) return null
        val now = clock.millis()
        val state = sceneBeliefState?.find(command.threadId) ?: return null
        return ParticipationMessageSignal(
            guildId = guildId,
            channelId = channelId,
            messageId = command.targetMessageId?.toLongOrNull() ?: 0,
            userId = userId,
            threadId = channelId.takeIf { command.threadId.contains(FOCUS_THREAD_MARKER) },
            sourceType = ParticipationMessageSourceType.HUMAN,
            mentioned = false,
            recentTurns = emptyList(),
            speakerLabel = "user_${userId % 100000}",
            pendingActionIds = listOf(command.waitActionIdentity),
            silenceMillis = 0,
            tsMs = now,
            sceneSeq = state.sceneSeq,
            contextVersion = state.contextVersion,
            seed = command.childDecisionId.hashCode().toLong() and Long.MAX_VALUE,
            turnGeneration = command.targetMessageId?.toLongOrNull() ?: return null,
            reevaluationWake = true,
            decisionIdOverride = command.childDecisionId,
            socialBeliefState = state.toJudgeState(),
        )
    }

    private fun SceneBeliefState.toJudgeState(): JudgeSocialBeliefState =
        JudgeSocialBeliefState(
            commonGround =
                commonGround.map {
                    JudgeCommonGroundState(it.code, it.confidence, it.evidenceRefs, it.status.name)
                },
            intentHypotheses =
                intentHypotheses.map {
                    JudgeIntentHypothesisState(
                        it.participantPseudonym,
                        it.code,
                        it.probability,
                        it.evidenceRefs,
                        it.status.name,
                    )
                },
            recentNiaActions =
                recentNiaActions.map {
                    JudgeRecentNiaActionState(
                        it.actionId,
                        it.actionKind,
                        it.intentSummary,
                        it.targetMessageRef,
                        it.contextVersion,
                    )
                },
            recentOutcomes =
                recentOutcomes.map {
                    JudgeRecentOutcomeState(
                        actionId = it.actionId,
                        code = it.code,
                        evidenceRef = it.evidenceRef,
                    )
                },
        )

    /** Construct an outcome for a failure before a [ParticipationMessageSignal] could be built. */
    fun failedMessageTurn(
        guildId: Long,
        channelId: Long,
    ): ParticipationTurnOutcome {
        val mode = flags.effectiveMode(guildId = guildId, channelId = channelId)
        return turnOutcome(mode, ParticipationEmitOutcome.Failed)
    }

    private fun turnOutcome(
        mode: ShadowMode,
        outcome: ParticipationEmitOutcome,
    ): ParticipationTurnOutcome =
        ParticipationTurnOutcome(
            outcome = outcome,
            // A real-send lane is configured to use FINAL. If that invariant is broken, the mode guard above returns
            // a silent failure and still owns the turn, so an unjudged legacy path cannot bypass it.
            ownsTurn = mode.allowsRealSend && outcome !== ParticipationEmitOutcome.Inactive,
        )

    private fun captureRawContext(signal: ParticipationMessageSignal): Boolean {
        val store = rawContextStore ?: return true
        return try {
            store.append(signal.toRawContextEntry())
            true
        } catch (e: Exception) {
            log.warn(
                "NEXA raw context 저장 실패(channel={}, message={}) — participation 평가 중단: {}",
                signal.channelId,
                signal.messageId,
                e.message,
            )
            false
        }
    }

    fun onMessageDeleted(signal: ParticipationRawContextRedactionSignal): ParticipationRawContextMutationOutcome =
        mutateRawContext(
            operation = "message_delete",
            guildId = signal.guildId,
            channelId = signal.channelId,
            messageId = signal.messageId,
        ) { store ->
            val result =
                store.redact(
                    scope = signal.scope(),
                    messageId = signal.messageId,
                    reason = RawContextUnavailableReason.REDACTED,
                )
            val evidenceRef = "raw_context_message:${messagePseudonym(signal.guildId, signal.messageId)}"
            pendingIntents?.invalidateBySource(evidenceRef)
            interactionOutcomes?.invalidateByEvidence(evidenceRef)
            ParticipationRawContextMutationOutcome.Redacted(if (result.removed) 1 else 0)
        }

    fun onMessageEdited(signal: ParticipationRawContextEditSignal): ParticipationRawContextMutationOutcome {
        if (signal.sourceType != ParticipationMessageSourceType.HUMAN || signal.rawText.isBlank() || signal.isCommandLike()) {
            return onMessageDeleted(signal.toRedactionSignal())
        }
        return mutateRawContext(
            operation = "message_edit",
            guildId = signal.guildId,
            channelId = signal.channelId,
            messageId = signal.messageId,
        ) { store ->
            store.append(signal.toRawContextEntry())
            ParticipationRawContextMutationOutcome.Upserted
        }
    }

    fun onChannelDisabled(
        guildId: Long,
        channelId: Long,
    ): ParticipationRawContextMutationOutcome =
        mutateRawContext(operation = "channel_disabled", guildId = guildId, channelId = channelId, messageId = null) { store ->
            val result =
                store.redactChannel(
                    guildId = guildId,
                    channelId = channelId,
                    reason = RawContextUnavailableReason.CONSENT_REVOKED,
                )
            ParticipationRawContextMutationOutcome.Redacted(result.removedCount)
        }

    fun onGuildDisabled(guildId: Long): ParticipationRawContextMutationOutcome =
        mutateRawContext(operation = "guild_disabled", guildId = guildId, channelId = null, messageId = null) { store ->
            val result = store.redactGuild(guildId = guildId, reason = RawContextUnavailableReason.CONSENT_REVOKED)
            ParticipationRawContextMutationOutcome.Redacted(result.removedCount)
        }

    fun onUserOptedOut(
        guildId: Long,
        userId: Long,
    ): ParticipationRawContextMutationOutcome =
        mutateRawContext(operation = "user_opted_out", guildId = guildId, channelId = null, messageId = null) { store ->
            val result =
                store.redactAuthor(
                    guildId = guildId,
                    authorPseudonym = userPseudonym(guildId, userId),
                    reason = RawContextUnavailableReason.CONSENT_REVOKED,
                )
            ParticipationRawContextMutationOutcome.Redacted(result.removedCount)
        }

    private fun mutateRawContext(
        operation: String,
        guildId: Long,
        channelId: Long?,
        messageId: Long?,
        block: (RawContextStorePort) -> ParticipationRawContextMutationOutcome,
    ): ParticipationRawContextMutationOutcome {
        val store = rawContextStore ?: return ParticipationRawContextMutationOutcome.NoStore
        return try {
            block(store)
        } catch (e: Exception) {
            log.warn(
                "NEXA raw context mutation 실패(operation={}, guild={}, channel={}, message={}) — {}",
                operation,
                guildId,
                channelId,
                messageId,
                e.message,
            )
            ParticipationRawContextMutationOutcome.Failed
        }
    }

    private fun ParticipationMessageSignal.toRawContextEntry(): RawContextEntry =
        RawContextEntry(
            scope = RawContextScope(guildId = guildId, channelId = channelId, threadId = threadId),
            messageId = messageId,
            authorPseudonym = userPseudonym(guildId, userId),
            occurredAt = recordedAtOf(this),
            replyToMessageId = replyToMessageId,
            sourceType = sourceType.toRawContextSourceType(),
            content = rawContextContent(),
        )

    private fun ParticipationRawContextEditSignal.toRawContextEntry(): RawContextEntry =
        RawContextEntry(
            scope = scope(),
            messageId = messageId,
            authorPseudonym = userPseudonym(guildId, userId),
            occurredAt = occurredAt,
            replyToMessageId = replyToMessageId,
            sourceType = sourceType.toRawContextSourceType(),
            content = RawContextContent.Available(rawText),
        )

    private fun ParticipationMessageSignal.rawContextContent(): RawContextContent {
        val text = rawText
        return if (text.isBlank()) {
            RawContextContent.Unavailable(RawContextUnavailableReason.EMPTY)
        } else {
            RawContextContent.Available(text)
        }
    }

    private fun ParticipationRawContextRedactionSignal.scope(): RawContextScope =
        RawContextScope(guildId = guildId, channelId = channelId, threadId = threadId)

    private fun ParticipationRawContextEditSignal.scope(): RawContextScope =
        RawContextScope(guildId = guildId, channelId = channelId, threadId = threadId)

    private fun ParticipationRawContextEditSignal.toRedactionSignal(): ParticipationRawContextRedactionSignal =
        ParticipationRawContextRedactionSignal(
            guildId = guildId,
            channelId = channelId,
            threadId = threadId,
            messageId = messageId,
        )

    private fun ParticipationRawContextEditSignal.isCommandLike(): Boolean = rawText.trimStart().startsWith(".")

    private fun evaluateAndEmit(
        signal: ParticipationMessageSignal,
        originRolloutMode: ShadowMode,
    ): ParticipationEmitOutcome {
        val guildPseudonym = guildPseudonym(signal.guildId)
        val userPseudonym = userPseudonym(signal.guildId, signal.userId)
        val channelKey = channelPseudonym(signal.guildId, signal.channelId)
        val sceneBuild = buildPolicyScene(signal, guildPseudonym, channelKey)
        val request =
            PolicyDecisionRequest(
                sceneSnapshotRef = sceneBuild.sceneSnapshot.ref,
                features = sceneBuild.featureVector,
                config =
                    PolicyConfigView(
                        channelMode = "participation",
                        autoRespondEnabled = false,
                        speechAllowed = true,
                    ),
                modelVersion = null,
                schemaVersion = SCHEMA_VERSION,
                seed = signal.seed,
            )

        recordShadowJudge(signal, sceneBuild)

        // FINAL 에서는 원문 장면과 few-shot 을 받은 single judge 만 의미를 판단한다. 아래 core/attention/baseline 은
        // 롤백용 OFF 및 비교용 SHADOW 경로에만 남겨, 키워드나 타이머가 생성형 판단보다 먼저 결론내리지 못하게 한다.
        if (judgeMode == NexaJudgeMode.FINAL) {
            return evaluateFinalJudge(
                signal = signal,
                guildPseudonym = guildPseudonym,
                userPseudonym = userPseudonym,
                channelKey = channelKey,
                sceneBuild = sceneBuild,
                originRolloutMode = originRolloutMode,
            )
        }

        // 0) core 결정론 규칙 게이트 먼저(CoreInterventionRules — hard_policy + rules 이식). 명백하면 즉결:
        //    이 호환 경로는 OFF/SHADOW 에서만 동작한다.
        val verdict = CoreInterventionRules.evaluate(ruleInputOf(signal))

        // 0-b) attention 타이밍 게이트(ChannelAttentionGate — "언제 깨우나"). 끼어들기 **판단**(verdict)과 직교한다.
        //      WAKE_NOW(멘션/호명/reply/continuation/pingpong) → 즉시 통과. WAIT(min_gap 연타·typing) → 이번 턴 보류
        //      (디바운스 등가 — 능동 타이머 없이 과발화 억제). DROP/NO_WAKE → 어차피 verdict 가 SILENT/Candidate.
        //      tsMs 미관측(0)이면 타이밍 신호가 없으므로 게이트를 건너뛴다(보수적·기존 동작 보존 — 첫 메시지/테스트).
        if (signal.tsMs > 0) {
            val wake = evaluateAttention(channelKey, signal, verdict)
            if (wake == AttentionGateConstants.WAIT) {
                logRuleDecision(
                    signal = signal,
                    guildPseudonym = guildPseudonym,
                    channelKey = channelKey,
                    actionKind = SocialActionKind.WAIT,
                    reasonCode = "ATTENTION_DEFERRED",
                )
                return ParticipationEmitOutcome.AttentionDeferred(channelKey)
            }
        }

        when (verdict) {
            is CoreInterventionRules.Verdict.Silent -> {
                logRuleDecision(
                    signal = signal,
                    guildPseudonym = guildPseudonym,
                    channelKey = channelKey,
                    actionKind = SocialActionKind.IGNORE,
                    reasonCode = verdict.reasonCode,
                )
                return ParticipationEmitOutcome.RuleSilent(verdict.reasonCode)
            }
            is CoreInterventionRules.Verdict.Wait -> {
                logRuleDecision(
                    signal = signal,
                    guildPseudonym = guildPseudonym,
                    channelKey = channelKey,
                    actionKind = SocialActionKind.WAIT,
                    reasonCode = verdict.reasonCode,
                )
                return ParticipationEmitOutcome.RuleWait(verdict.reasonCode)
            }
            is CoreInterventionRules.Verdict.Speak -> {
                // 규칙이 명백한 SPEAK 라 정책 분포를 묻지 않는다 — 결정론 ALWAYS_SPEAK 분포로 emit 한다.
                return emitSpeak(
                    signal = signal,
                    guildPseudonym = guildPseudonym,
                    userPseudonym = userPseudonym,
                    channelKey = channelKey,
                    response = ruleForcedSpeakResponse(),
                    originRolloutMode = originRolloutMode,
                )
            }
            CoreInterventionRules.Verdict.Candidate -> Unit // 모호 → 아래 정책 분포로 위임.
        }

        val response = policy.decide(request)

        if (!turnGenerations.isLatest(signal.channelId, signal.turnGeneration)) {
            return ParticipationEmitOutcome.Superseded(NiaTurnSupersessionStage.AFTER_JUDGE)
        }

        // 2) SPEAK 가 가장 유력하지 않으면 emit 를 부르지 않는다(IGNORE/REACT/WAIT 는 발화 없음). emit 가 안전 override 로
        //    다시 한 번 접지만, 여기서 먼저 거르면 불필요한 발화 파이프라인·생성 비용을 피한다(KISS).
        if (response.mostLikelyAction != com.discordassistant.central.participation.domain.model.action.SocialActionKind.SPEAK) {
            logPolicyDecision(
                signal = signal,
                guildPseudonym = guildPseudonym,
                channelKey = channelKey,
                response = response,
                actionKind = response.mostLikelyAction,
                reasonCode = "POLICY_ARGMAX_${response.mostLikelyAction.name}",
                featureVector = sceneBuild.featureVector,
            )
            return ParticipationEmitOutcome.NotSpeaking(response.mostLikelyAction)
        }
        return emitSpeak(
            signal = signal,
            guildPseudonym = guildPseudonym,
            userPseudonym = userPseudonym,
            channelKey = channelKey,
            response = response,
            featureVector = sceneBuild.featureVector,
            originRolloutMode = originRolloutMode,
        )
    }

    private fun buildPolicyScene(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
    ): SingleJudgeSceneBuildResult = SingleJudgeSceneSnapshotBuilder.build(sceneObservationOf(signal, guildPseudonym, channelKey))

    private fun sceneObservationOf(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
    ): SingleJudgeSceneObservation =
        SingleJudgeSceneObservation(
            ref =
                SceneSnapshotRef(
                    guildPseudonym = guildPseudonym,
                    channelId = channelKey,
                    sceneSeq = signal.sceneSeq,
                    contextVersion = signal.contextVersion,
                ),
            triggerText = signal.triggerText.takeIf { it.isNotBlank() },
            directAddressed = signal.mentioned,
            replyToNia = signal.replyToNia,
            replyToHuman = signal.replyToHuman,
            conversationMentionsNia = signal.conversationMentionsNia,
            recentAgentBurstCount = signal.recentAgentBurstCount,
            silenceMillis = signal.silenceMillis,
            lastNiaSpokeAgeSeconds = signal.lastNiaSpokeAgeSeconds,
            pendingActionIds = signal.pendingActionIds,
            humanLikelyAnswering = signal.humanLikelyAnswering,
            resolvedLikely = signal.resolvedLikely,
            niaTurnContinuationLikely = signal.niaTurnContinuationLikely,
            directAddressPressure = signal.directAddressPressure,
            replyChainDepth = signal.replyChainDepth,
            nicknameCall = signal.nicknameCall || containsNiaNameSignal(signal.triggerText),
            previousIgnoredRequestCount = signal.previousIgnoredRequestCount,
            humansTalkingToEachOtherLikely = signal.humansTalkingToEachOtherLikely,
            rateLimitPressure = signal.rateLimitPressure,
            antiSpamPressure = signal.antiSpamPressure,
            knownHumanDisplayNames =
                signal.recentRawMessages
                    .asSequence()
                    .filterNot { it.bot }
                    .map { it.authorLabel }
                    .filter { it.isNotBlank() }
                    .toSet(),
            relationshipObservation = signal.relationshipObservation,
            memoryObservation = signal.memoryObservation,
            socialBeliefState = signal.socialBeliefState,
        )

    private fun recordShadowJudge(
        signal: ParticipationMessageSignal,
        sceneBuild: SingleJudgeSceneBuildResult,
    ) {
        if (judgeMode != NexaJudgeMode.SHADOW) return
        val shadowService = judgeShadowService ?: return
        val request = singleJudgeRequest(signal, guildPseudonym(signal.guildId), signal.channelId.toString(), sceneBuild)
        when (val result = shadowService.record(request)) {
            is NiaJudgeShadowResult.Recorded -> Unit
            is NiaJudgeShadowResult.Failed ->
                log.warn("NEXA single judge shadow 기록 실패(sceneSeq={}) — {}", signal.sceneSeq, result.reason)
        }
    }

    private fun evaluateFinalJudge(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        userPseudonym: String,
        channelKey: String,
        sceneBuild: SingleJudgeSceneBuildResult,
        originRolloutMode: ShadowMode,
    ): ParticipationEmitOutcome {
        val judge = singleJudge
        val request = singleJudgeRequest(signal, guildPseudonym, channelKey, sceneBuild)
        if (judge == null) {
            logSingleJudgeDecision(
                signal = signal,
                guildPseudonym = guildPseudonym,
                channelKey = channelKey,
                request = request,
                decision = null,
                actionKind = SocialActionKind.IGNORE,
                reasonCode = "SINGLE_JUDGE_UNAVAILABLE",
                featureVector = sceneBuild.featureVector,
                shadowBaselineAction = null,
            )
            return ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE)
        }

        val decision =
            try {
                judge.decide(request)
            } catch (e: Exception) {
                log.warn("NEXA single judge final 판단 실패(sceneSeq={}) — {}", signal.sceneSeq, e::class.simpleName)
                logSingleJudgeDecision(
                    signal = signal,
                    guildPseudonym = guildPseudonym,
                    channelKey = channelKey,
                    request = request,
                    decision = null,
                    actionKind = SocialActionKind.IGNORE,
                    reasonCode = "SINGLE_JUDGE_FAILED",
                    featureVector = sceneBuild.featureVector,
                    shadowBaselineAction = null,
                )
                return ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE)
            }

        if (!turnGenerations.isLatest(signal.channelId, signal.turnGeneration)) {
            logSingleJudgeDecision(
                signal = signal,
                guildPseudonym = guildPseudonym,
                channelKey = channelKey,
                request = request,
                decision = decision,
                actionKind = decision.action,
                reasonCode = NiaTurnSupersessionStage.AFTER_JUDGE.reasonCode,
                featureVector = sceneBuild.featureVector,
                shadowBaselineAction = null,
            )
            return ParticipationEmitOutcome.Superseded(NiaTurnSupersessionStage.AFTER_JUDGE)
        }

        if (!applyJudgeBeliefDelta(signal, decision)) {
            log.warn("NEXA judge 믿음 갱신 중 장면 버전 충돌(sceneSeq={}) — stale 결정 실행 안 함", signal.sceneSeq)
            return ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE)
        }

        if (decision.action == SocialActionKind.SPEAK) {
            return emitSpeak(
                signal = signal,
                guildPseudonym = guildPseudonym,
                userPseudonym = userPseudonym,
                channelKey = channelKey,
                response = decision.toPolicyResponse(),
                featureVector = sceneBuild.featureVector,
                attribution =
                    decision.toSingleJudgeAttribution(
                        request = request,
                        shadowBaselineAction = null,
                    ),
                originRolloutMode = originRolloutMode,
            )
        }

        logSingleJudgeDecision(
            signal = signal,
            guildPseudonym = guildPseudonym,
            channelKey = channelKey,
            request = request,
            decision = decision,
            actionKind = decision.action,
            reasonCode = decision.reasonCode.code,
            featureVector = sceneBuild.featureVector,
            shadowBaselineAction = null,
        )
        val routed = routeNonSpeechJudgeAction(signal, guildPseudonym, channelKey, decision, originRolloutMode)
        if (decision.action == SocialActionKind.IGNORE || routed) {
            recordSceneAction(signal, decision.action, decision.speechIntent?.intentSummary)
        }
        return ParticipationEmitOutcome.NotSpeaking(decision.action)
    }

    private fun applyJudgeBeliefDelta(
        signal: ParticipationMessageSignal,
        decision: SingleJudgeDecision,
    ): Boolean {
        val store = sceneBeliefState ?: return true
        val delta =
            SceneBeliefDelta(
                commonGround =
                    decision.beliefDelta.commonGround.map {
                        CommonGroundBelief(
                            code = it.code,
                            confidence = it.confidence,
                            evidenceRefs = it.evidenceRefs,
                            status = BeliefStatus.valueOf(it.status.name),
                        )
                    },
                intentHypotheses =
                    decision.beliefDelta.intentHypotheses.map {
                        IntentHypothesisBelief(
                            participantPseudonym = it.participantRef,
                            code = it.code,
                            probability = it.probability,
                            evidenceRefs = it.evidenceRefs,
                            status = BeliefStatus.valueOf(it.status.name),
                        )
                    },
            )
        val applied =
            store.applyDelta(
                focusThreadKey = focusThreadKey(signal, guildPseudonym(signal.guildId)),
                expectedContextVersion = signal.contextVersion,
                delta = delta,
            ) != null
        if (applied) applyCommitmentUpdates(signal, decision)
        return applied
    }

    private fun applyCommitmentUpdates(
        signal: ParticipationMessageSignal,
        decision: SingleJudgeDecision,
    ) {
        val store = pendingIntents ?: return
        val guild = guildPseudonym(signal.guildId)
        val focus = focusThreadKey(signal, guild)
        val availableEvidence = signal.evidenceRefs(guild)
        decision.beliefDelta.commitments
            .filter { it.confidence >= MIN_COMMITMENT_CONFIDENCE && availableEvidence.containsAll(it.evidenceRefs) }
            .forEach { update ->
                val id = commitmentId(focus, update.commitmentRef)
                when (update.status) {
                    com.discordassistant.central.participation.application.judge.JudgeCommitmentStatus.ACTIVE ->
                        store.save(
                            PendingIntent(
                                id = id,
                                visibility = VisibilityScope.Channel(guild, channelPseudonym(signal.guildId, signal.channelId)),
                                topic = update.topic,
                                targetPseudonym = userPseudonym(signal.guildId, signal.userId),
                                socialAct = SocialAct.valueOf(update.socialAct),
                                activation = IntentActivation.IMMEDIATE,
                                urgency = IntentUrgency.NORMAL,
                                source = MemorySource(update.evidenceRefs, 1, true, recordedAtOf(signal)),
                                expiresAt = recordedAtOf(signal).plus(COMMITMENT_TTL),
                                confidence = update.confidence,
                                focusThreadKey = focus,
                            ),
                        )
                    // 판단 모델은 완료를 주장할 수 있지만, 실제 SEND 실행 증거만 약속을 닫는다.
                    com.discordassistant.central.participation.application.judge.JudgeCommitmentStatus.COMPLETED -> Unit
                    com.discordassistant.central.participation.application.judge.JudgeCommitmentStatus.REJECTED -> store.invalidate(id)
                }
            }
    }

    private fun commitmentId(
        focusThreadKey: String,
        commitmentRef: String,
    ): String = sha256Hex("$focusThreadKey:$commitmentRef")

    private fun recordSceneAction(
        signal: ParticipationMessageSignal,
        action: SocialActionKind,
        intentSummary: String?,
    ) {
        sceneBeliefState?.recordAction(
            focusThreadKey(signal, guildPseudonym(signal.guildId)),
            RecentNiaActionBelief(
                actionId = correlationIdOf(signal),
                actionKind = action.name.lowercase(),
                intentSummary = intentSummary?.take(RecentNiaActionBelief.MAX_INTENT_CHARS),
                targetMessageRef = "raw_context_message:${messagePseudonym(signal.guildId, signal.messageId)}",
                contextVersion = signal.contextVersion,
                occurredAt = recordedAtOf(signal),
            ),
        )
    }

    private fun singleJudgeRequest(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
        sceneBuild: SingleJudgeSceneBuildResult,
    ): SingleJudgeDecisionRequest =
        judgeContextAssembler.assemble(
            NiaJudgeContextInput(
                rawContextSnapshot = rawContextSnapshot(signal),
                sceneObservation = sceneObservationOf(signal, guildPseudonym, channelKey),
                sceneBuild = sceneBuild,
                constraints = judgeConstraints(),
                seed = signal.seed,
                fewShotSet = activeFewShotPayload(signal),
            ),
        )

    private fun rawContextSnapshot(signal: ParticipationMessageSignal): RawContextSnapshot {
        val scope = RawContextScope(guildId = signal.guildId, channelId = signal.channelId, threadId = signal.threadId)
        val recentEntries = signal.recentRawMessages.mapNotNull { it.toRawContextEntry(scope, signal.guildId) }
        val stored =
            try {
                rawContextStore?.readRecent(scope) ?: RawContextSnapshot(scope, emptyList())
            } catch (e: Exception) {
                log.warn("NEXA judge raw context 조회 실패(sceneSeq={}) — {}", signal.sceneSeq, e.message)
                RawContextSnapshot(scope, emptyList())
            }
        val merged =
            (stored.entries + recentEntries)
                .distinctBy { it.messageId }
                .sortedWith(compareBy<RawContextEntry> { it.occurredAt }.thenBy { it.messageId })
        return RawContextSnapshot(scope, merged)
    }

    private fun ParticipationRawSceneMessage.toRawContextEntry(
        scope: RawContextScope,
        guildId: Long,
    ): RawContextEntry? {
        val text = content.trim()
        if (text.isBlank()) return null
        return RawContextEntry(
            scope = scope,
            messageId = positiveRawContextMessageId(messageId),
            authorPseudonym = if (bot) NIA_RAW_CONTEXT_AUTHOR_PSEUDONYM else userPseudonym(guildId, authorId),
            occurredAt = if (occurredAtMs > 0) Instant.ofEpochMilli(occurredAtMs) else Instant.EPOCH,
            replyToMessageId = replyToMessageId?.let(::positiveRawContextMessageId),
            sourceType = if (bot) RawContextSourceType.BOT else RawContextSourceType.HUMAN,
            content = RawContextContent.Available(text),
        )
    }

    private fun positiveRawContextMessageId(id: Long): Long = if (id > 0) id else (Long.MAX_VALUE + id).coerceAtLeast(1L)

    private fun judgeConstraints(): JudgeDecisionConstraints =
        JudgeDecisionConstraints(
            allowedActions = SocialActionKind.entries.toSet(),
            speechAllowed = true,
            reactionAllowed = true,
            maxDelayMillis = 30_000,
        )

    private fun activeFewShotPayload(signal: ParticipationMessageSignal): JudgeFewShotSetPayload {
        val set =
            fewShotService
                ?.activeFor(NiaFewShotLookupScope(guildId = signal.guildId, channelId = signal.channelId))
                ?: return DEFAULT_FEW_SHOT_PAYLOAD
        val active = set.active ?: return DEFAULT_FEW_SHOT_PAYLOAD
        val setId = set.id ?: return DEFAULT_FEW_SHOT_PAYLOAD
        return active.toJudgePayload(setId)
    }

    private fun speechIdentity(signal: ParticipationMessageSignal): IdentityKernelSection {
        val active =
            fewShotService
                ?.activeFor(NiaFewShotLookupScope(guildId = signal.guildId, channelId = signal.channelId))
                ?.active
        val managedPrompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(active)
        return NIA_IDENTITY.copy(personaBlock = "${NIA_IDENTITY.personaBlock}\n\n$managedPrompt")
    }

    private fun NiaFewShotVersion.toJudgePayload(setId: Long): JudgeFewShotSetPayload =
        JudgeFewShotSetPayload(
            setId = setId,
            version = version,
            examples =
                examples
                    .sortedWith(compareByDescending<NiaFewShotExample> { it.priority }.thenBy { it.id ?: Long.MAX_VALUE })
                    .mapIndexed { index, example -> example.toJudgePayload(setId, version, index) },
        )

    private fun NiaFewShotExample.toJudgePayload(
        setId: Long,
        version: Int,
        index: Int,
    ): JudgeFewShotExamplePayload =
        JudgeFewShotExamplePayload(
            exampleId = "fewshot_${setId}_${version}_${id ?: index}",
            title = title,
            rawMessages =
                rawMessages.map { message ->
                    JudgeFewShotRawMessagePayload(
                        ref = message.ref,
                        authorRole = message.authorRole,
                        offsetMs = message.offsetMs,
                        text = message.text,
                    )
                },
            expectedAction = expectedAction,
            expectedDeliveryMode = expectedDeliveryMode,
            expectedReplies = expectedReplies,
            currentState = currentState,
            expectedReactionCode = expectedReactionCode,
            expectedReevaluateAfterMs = expectedReevaluateAfterMs,
            reason = reason,
            evidenceRefs = evidenceRefs,
            badAlternative =
                JudgeFewShotBadAlternativePayload(
                    action = badAlternative.action,
                    whyBad = badAlternative.whyBad,
                    deliveryMode = badAlternative.deliveryMode,
                ),
            tags = tags,
            priority = priority,
            privacyClass = privacyClass,
        )

    /**
     * 채널 attention 상태를 갱신하고 이 트리거의 깨움 action([AttentionGateConstants] WAKE_NOW/WAIT/WAKE_AFTER_IDLE)을 낸다.
     * core verdict 를 hard_policy 로 사상한다: SPEAK(호명/reply/continuation)→RESPOND_NOW(즉시 wake), Silent→DROP,
     * 그 외→CANDIDATE(idle 후 — 디바운스 등가). 채널 상태 단위로 동기화해 동시 메시지의 in-place 갱신 경합을 막는다.
     */
    private fun evaluateAttention(
        channelKey: String,
        signal: ParticipationMessageSignal,
        verdict: CoreInterventionRules.Verdict,
    ): String {
        val hardPolicy =
            when (verdict) {
                is CoreInterventionRules.Verdict.Speak -> ChannelAttentionGate.HARD_RESPOND_NOW
                is CoreInterventionRules.Verdict.Silent -> ChannelAttentionGate.HARD_DROP
                else -> ChannelAttentionGate.HARD_CANDIDATE
            }
        val state = attentionStates.computeIfAbsent(channelKey) { ChannelAttentionGate.ChannelAttentionState() }
        // 트리거 메시지는 사람 발화(니아 자기 메시지는 이 경로로 안 옴 — DiscordBot 이 봇 author 를 早期 return).
        // isNia=false 로 평가하고, 니아 발화 앵커(last_nia_ts)는 emit 성공 시 갱신한다(pingpong wake 기준점).
        return synchronized(state) {
            ChannelAttentionGate.decide(tsMs = signal.tsMs, isNia = false, hardPolicy = hardPolicy, state = state).action
        }
    }

    /** emit(니아 발화) 직후 pingpong 앵커(last_nia_ts)를 트리거 시각으로 갱신한다(다음 사람 응답이 핑퐁 창에 들도록). */
    private fun markNiaSpoke(
        channelKey: String,
        tsMs: Long,
    ) {
        if (tsMs <= 0) return
        val state = attentionStates.computeIfAbsent(channelKey) { ChannelAttentionGate.ChannelAttentionState() }
        synchronized(state) { state.lastNiaTsMs = tsMs }
    }

    /**
     * [signal] 로 [CoreInterventionRules.RuleInput] 을 만든다(브리지가 가진 raw 신호 → 순수 규칙 입력).
     * 7개 히스토리 도출 신호(continuation·중복·burst 미완·사적 핑퐁)를 그대로 매핑한다 — 호출자(DiscordBot)가 채널
     * 최근 메시지 히스토리에서 도출해 [signal] 에 채워 넘긴다. 미관측이면 RuleInput 기본값(보수적 — 덜 발화)으로 떨어진다.
     */
    private fun ruleInputOf(signal: ParticipationMessageSignal): CoreInterventionRules.RuleInput =
        CoreInterventionRules.RuleInput(
            triggerText = signal.triggerText,
            speakerLabel = signal.speakerLabel,
            mentioned = signal.mentioned,
            replyToNia = signal.replyToNia,
            niaRecentTokens = signal.niaRecentTokens,
            withinContinuationTtl = signal.withinContinuationTtl,
            burstIncomplete = signal.burstIncomplete,
            duplicateOfPrevHuman = signal.duplicateOfPrevHuman,
            priorHumanSpeakerLabels = signal.priorHumanSpeakerLabels,
            firstMessageText = signal.firstMessageText,
            conversationMentionsNia = signal.conversationMentionsNia,
        )

    /**
     * 잠정 SPEAK(규칙 즉결 또는 정책 분포 argmax) 후 공통 경로 — emit 입력 조립 → 완전 행동 후보 평가 → 행동 예약.
     * [response] 는 규칙 즉결이면 [ruleForcedSpeakResponse], 정책 위임이면 정책이 낸 분포다.
     */
    private fun emitSpeak(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        userPseudonym: String,
        channelKey: String,
        response: PolicyDecisionResponse,
        featureVector: FeatureVectorView? = null,
        attribution: DecisionAttribution = response.defaultAttribution(),
        originRolloutMode: ShadowMode,
    ): ParticipationEmitOutcome {
        if (!turnGenerations.isLatest(signal.channelId, signal.turnGeneration)) {
            return ParticipationEmitOutcome.Superseded(NiaTurnSupersessionStage.BEFORE_SPEECH_GENERATION)
        }
        logPolicyDecision(
            signal = signal,
            guildPseudonym = guildPseudonym,
            channelKey = channelKey,
            response = response,
            actionKind = SocialActionKind.SPEAK,
            reasonCode = attribution.reasonCode,
            featureVector = featureVector,
            attribution = attribution,
        )

        // 3) emit 입력 조립 — 이 시점까지의 최근 대화 turn 을 packet 으로(원문 비저장 가명 라벨), 동의 가명 키는
        //    PolicyBackedConsentGate 형식(guild:user:channel)으로 맞춘다.
        val openCommitment = pendingIntents?.findActive(focusThreadKey(signal, guildPseudonym), Instant.now(clock))?.firstOrNull()
        val effectiveSpeechIntent =
            (attribution.speechIntent ?: response.speechIntent(reasonCode = attribution.reasonCode)) +
                if (openCommitment != null) {
                    "; open_commitment=true; commitment_act=${openCommitment.socialAct.name}; " +
                        "commitment_topic=${openCommitment.topic}; 미완료 약속을 실제 행위로 해결한다"
                } else {
                    ""
                }
        val packet =
            SpeechScenePacket.of(
                focusThreadKey = focusThreadKey(signal, guildPseudonym),
                target = SpeechTarget.member(userPseudonym),
                recentTurns = signal.recentTurns,
                socialAct = response.selectedSpeechSocialAct(),
                burstShape = response.toSpeechBurstShape(),
                identity = speechIdentity(signal),
                speechIntent = effectiveSpeechIntent,
                rawContextSceneData = rawContextSceneDataForSpeech(signal),
                responseTargetRef = attribution.responseTargetRef,
                responseObligation = attribution.responseObligation,
                groundingNeed = attribution.groundingNeed,
            )
        val emitRequest =
            rawWindowTrace(signal).let { rawTrace ->
                NexaSpeechEmitRequest(
                    provenance =
                        DecisionProvenance(
                            correlationId = "participation:$channelKey:${signal.sceneSeq}",
                            guildPseudonym = guildPseudonym,
                            channelId = channelKey,
                            contextVersion = signal.contextVersion,
                            featureHash = featureHashOf(signal),
                            featureVectorVersion = FeatureCatalog.VERSION,
                            modelVersion = response.modelVersion,
                            judgeModelVersion = attribution.judgeModelVersion,
                            judgePromptVersion = attribution.judgePromptVersion,
                            fewShotSetId = attribution.fewShotSetId,
                            fewShotVersion = attribution.fewShotVersion,
                            rawWindowHash = rawTrace.hash,
                            rawWindowMessageRefs = rawTrace.messageRefs,
                            reasonCode = attribution.reasonCode,
                            judgeConfidence = attribution.judgeConfidence,
                            decisionDelayMillis = attribution.decisionDelayMillis,
                            lastWakeUpReason = signal.wakeUpReasonCode(),
                            missingInputCodes = signal.missingInputCodes(),
                            evidenceRefs = signal.evidenceRefs(guildPseudonym),
                            shadowBaselineAction = attribution.shadowBaselineAction,
                            finalDecisionSource = attribution.finalDecisionSource,
                        ),
                    rawDistribution = response.toDomain(),
                    safetyContext = BanterSafetyContext(),
                    packet = packet,
                    consentSubjectPseudonym =
                        PolicyBackedConsentGate.pseudonymOf(
                            guildId = signal.guildId,
                            userId = signal.userId,
                            channelId = signal.channelId,
                        ),
                    actionTarget = actionTarget(signal, guildPseudonym, channelKey),
                    sampledActionIndex = 0,
                    seed = signal.seed,
                    executeAfter = Instant.now(),
                    originRolloutMode = originRolloutMode,
                    turnGeneration = signal.turnGeneration,
                    budget =
                        GenerationBudget.forDecision(
                            uncertainty = response.uncertainty,
                            hasOpenCommitment = packet.speechIntent?.contains("open_commitment=true") == true,
                        ),
                    executionLimits = ExecutionLimits(perChannel = perChannelPerMin, global = globalPerMin),
                    fulfillsPendingIntentId = openCommitment?.id,
                )
            }
        val result = emit.emit(emitRequest)
        if (result.superseded) {
            return ParticipationEmitOutcome.Superseded(NiaTurnSupersessionStage.BEFORE_SCHEDULE)
        }
        if (
            result.routeResult is com.discordassistant.central.actionruntime.application.RouteResult.Ignored &&
            (
                result.pipelineResult?.outcome ==
                    com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome.SPEAK ||
                    result.pipelineResult?.outcome ==
                    com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome.REACTION_ONLY
            )
        ) {
            recordSceneAction(signal, SocialActionKind.IGNORE, attribution.speechIntent)
            return ParticipationEmitOutcome.SchedulingRejected(channelKey)
        }
        val actualAction =
            when {
                result.willSpeak -> SocialActionKind.SPEAK
                result.willReact -> SocialActionKind.REACT
                else -> SocialActionKind.IGNORE
            }
        recordSceneAction(signal, actualAction, attribution.speechIntent)
        if (result.willSpeak) {
            // 실제 발화 예약만 pingpong 앵커를 갱신한다. critic 차단·취소는 발화로 세지 않는다.
            markNiaSpoke(channelKey, signal.tsMs)
        }
        return ParticipationEmitOutcome.Emitted(result)
    }

    private fun recordTrace(
        signal: ParticipationMessageSignal,
        mode: ShadowMode,
        outcome: ParticipationEmitOutcome,
    ): ParticipationEmitOutcome {
        val trace =
            ParticipationGateTrace(
                correlationId = correlationIdOf(signal),
                guildId = signal.guildId,
                channelId = signal.channelId,
                sceneSeq = signal.sceneSeq,
                contextVersion = signal.contextVersion,
                recordedAt = recordedAtOf(signal),
                mode = mode,
                outcome = outcome.traceOutcome,
                reasonCode = outcome.traceReasonCode,
                policyAction = outcome.tracePolicyAction,
                safeAction = outcome.traceSafeAction,
                speechOutcome = outcome.traceSpeechOutcome,
                consentStage = outcome.traceConsentStage,
                willSpeak = outcome.traceWillSpeak,
                currentConversation =
                    signal.recentTurns.map { turn ->
                        ParticipationTraceMessage(speaker = turn.speakerLabel, text = turn.text)
                    },
                niaReply =
                    (outcome as? ParticipationEmitOutcome.Emitted)
                        ?.result
                        ?.pipelineResult
                        ?.selected
                        ?.bubbles
                        .orEmpty(),
                features =
                    ParticipationGateTraceFeatures(
                        mentioned = signal.mentioned,
                        replyToNia = signal.replyToNia,
                        duplicateOfPrevHuman = signal.duplicateOfPrevHuman,
                        burstIncomplete = signal.burstIncomplete,
                        conversationMentionsNia = signal.conversationMentionsNia,
                        recentAgentBurstCount = signal.recentAgentBurstCount,
                        hasTimestamp = signal.tsMs > 0,
                    ),
            )
        traceStore.append(trace)
        if (mode.evaluatesPolicy) {
            log.info(
                "NIA_TURN_RESULT trace={} mode={} outcome={} reason={} policy={} speech={} willSpeak={}",
                diagnosticTraceOf(signal),
                trace.mode,
                trace.outcome,
                trace.reasonCode,
                trace.policyAction,
                trace.speechOutcome,
                trace.willSpeak,
            )
        }
        return outcome
    }

    /** raw guildId → 저장 키 가명(MEMORY purpose, 길드 스코프). ShadowMode store/flag 와 같은 가명 공간. */
    private fun guildPseudonym(guildId: Long): String =
        ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = guildId)

    /** raw userId → 길드 스코프 가명(원문 user id 비저장 — packet/target 라벨). */
    private fun userPseudonym(
        guildId: Long,
        userId: Long,
    ): String = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = userId)

    private fun channelPseudonym(
        guildId: Long,
        channelId: Long,
    ): String = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = channelId)

    private fun messagePseudonym(
        guildId: Long,
        messageId: Long,
    ): String = ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = messageId)

    private fun correlationIdOf(signal: ParticipationMessageSignal): String =
        signal.decisionIdOverride ?: "participation:${channelPseudonym(signal.guildId, signal.channelId)}:${signal.sceneSeq}"

    private fun diagnosticTraceOf(signal: ParticipationMessageSignal): String = sha256Hex(correlationIdOf(signal)).take(12)

    private fun recordedAtOf(signal: ParticipationMessageSignal): Instant =
        if (signal.tsMs > 0) Instant.ofEpochMilli(signal.tsMs) else Instant.EPOCH

    private fun PolicyDecisionResponse.reasonCodeForDecision(): String =
        if (modelVersion == RULE_FORCED_MODEL_VERSION) "RULE_FORCED_SPEAK" else "POLICY_ARGMAX_SPEAK"

    private fun PolicyDecisionResponse.selectedSpeechSocialAct(): SpeechSocialAct {
        val selected = socialActWeights.entries.maxByOrNull { it.value }?.key
        return SpeechSocialAct.fromWireName(selected?.wireName ?: SpeechSocialAct.ACKNOWLEDGE.wireName)
    }

    private fun PolicyDecisionResponse.toSpeechBurstShape(): SpeechBurstShape =
        SpeechBurstShape(
            fragmentCount = burstProfile.mostLikelyFragmentCount,
            maxFragmentLength = burstProfile.maxFragmentLength,
            reactionOnly = false,
        )

    private fun PolicyDecisionResponse.speechIntent(reasonCode: String): String =
        buildString {
            append("participation_action=SPEAK; ")
            append("reason_code=$reasonCode; ")
            append("social_act=${selectedSpeechSocialAct().wireName}; ")
            append("fragments=${burstProfile.mostLikelyFragmentCount}; ")
            append("speech는 이 방향을 수행하는 실제 문구 후보를 만들고, 침묵·리액션과 결과를 비교해 최종 행동을 고른다.")
        }

    private fun logRuleDecision(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
        actionKind: SocialActionKind,
        reasonCode: String,
    ) {
        val rawTrace = rawWindowTrace(signal)
        appendDecisionLog(
            DecisionLogRecord(
                correlationId = correlationIdOf(signal),
                guildPseudonym = guildPseudonym,
                channelId = channelKey,
                contextVersion = signal.contextVersion,
                actionKind = actionKind,
                featureHash = featureHashOf(signal),
                featureVectorVersion = FeatureCatalog.VERSION,
                modelVersion = RULE_FORCED_MODEL_VERSION,
                rawWindowHash = rawTrace.hash,
                rawWindowMessageRefs = rawTrace.messageRefs,
                seed = signal.seed,
                removedKinds = emptySet(),
                reasonCode = reasonCode,
                judgeConfidence = 1.0,
                decisionDelayMillis = if (actionKind == SocialActionKind.WAIT) 1_000 else null,
                lastWakeUpReason = signal.wakeUpReasonCode(),
                missingInputCodes = signal.missingInputCodes(),
                evidenceRefs = signal.evidenceRefs(guildPseudonym),
                finalDecisionSource = ruleFinalDecisionSource(),
                consumedGenerationQuota = false,
                decidedAt = recordedAtOf(signal),
            ),
        )
    }

    private fun logPolicyDecision(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
        response: PolicyDecisionResponse,
        actionKind: SocialActionKind,
        reasonCode: String,
        featureVector: FeatureVectorView? = null,
        attribution: DecisionAttribution = response.defaultAttribution(reasonCode = reasonCode),
    ) {
        val rawTrace = rawWindowTrace(signal)
        appendDecisionLog(
            DecisionLogRecord(
                correlationId = correlationIdOf(signal),
                guildPseudonym = guildPseudonym,
                channelId = channelKey,
                contextVersion = signal.contextVersion,
                actionKind = actionKind,
                featureHash = featureVector?.let { featureHashOf(it) } ?: featureHashOf(signal),
                featureVectorVersion = FeatureCatalog.VERSION,
                modelVersion = response.modelVersion,
                judgeModelVersion = attribution.judgeModelVersion,
                judgePromptVersion = attribution.judgePromptVersion,
                fewShotSetId = attribution.fewShotSetId,
                fewShotVersion = attribution.fewShotVersion,
                rawWindowHash = rawTrace.hash,
                rawWindowMessageRefs = rawTrace.messageRefs,
                seed = signal.seed,
                removedKinds = emptySet(),
                reasonCode = attribution.reasonCode,
                judgeConfidence = attribution.judgeConfidence,
                decisionDelayMillis = attribution.decisionDelayMillis,
                lastWakeUpReason = signal.wakeUpReasonCode(),
                missingInputCodes = signal.missingInputCodes(),
                evidenceRefs = signal.evidenceRefs(guildPseudonym),
                shadowBaselineAction = attribution.shadowBaselineAction,
                finalDecisionSource = attribution.finalDecisionSource,
                consumedGenerationQuota = actionKind == SocialActionKind.SPEAK,
                decidedAt = recordedAtOf(signal),
            ),
        )
    }

    private fun logSingleJudgeDecision(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
        request: SingleJudgeDecisionRequest,
        decision: SingleJudgeDecision?,
        actionKind: SocialActionKind,
        reasonCode: String,
        featureVector: FeatureVectorView,
        shadowBaselineAction: SocialActionKind?,
    ) {
        val rawTrace = rawWindowTrace(signal)
        appendDecisionLog(
            DecisionLogRecord(
                correlationId = correlationIdOf(signal),
                guildPseudonym = guildPseudonym,
                channelId = channelKey,
                contextVersion = signal.contextVersion,
                actionKind = actionKind,
                featureHash = featureHashOf(featureVector),
                featureVectorVersion = FeatureCatalog.VERSION,
                modelVersion = SINGLE_JUDGE_FINAL_MODEL_VERSION,
                judgeModelVersion = SINGLE_JUDGE_FINAL_MODEL_VERSION,
                judgePromptVersion = NiaJudgePromptAssembler.PROMPT_VERSION,
                fewShotSetId = request.fewShotSet.setId?.toString(),
                fewShotVersion = request.fewShotSet.version,
                rawWindowHash = rawTrace.hash,
                rawWindowMessageRefs = rawTrace.messageRefs,
                seed = signal.seed,
                removedKinds = emptySet(),
                reasonCode = reasonCode,
                judgeConfidence = decision?.confidence,
                decisionDelayMillis = decision?.delay?.millis,
                lastWakeUpReason = signal.wakeUpReasonCode(),
                missingInputCodes = signal.missingInputCodes(),
                evidenceRefs = signal.evidenceRefs(guildPseudonym),
                shadowBaselineAction = shadowBaselineAction,
                finalDecisionSource = SINGLE_JUDGE_DECISION_SOURCE,
                consumedGenerationQuota = false,
                decidedAt = recordedAtOf(signal),
            ),
        )
    }

    private fun routeNonSpeechJudgeAction(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
        decision: SingleJudgeDecision,
        originRolloutMode: ShadowMode,
    ): Boolean {
        val socialAction = decision.toSocialAction(signal)
        if (
            socialAction.kind != SocialActionKind.WAIT &&
            socialAction.kind != SocialActionKind.REACT &&
            socialAction.kind != SocialActionKind.CANCEL_PENDING
        ) {
            return false
        }
        val router = actionRouter
        if (router == null) {
            log.warn("NEXA final judge {} 라우터 없음(sceneSeq={}) — speech 없이 종료", socialAction.kind, signal.sceneSeq)
            return false
        }
        return runCatching {
            router.route(
                decisionId = correlationIdOf(signal),
                sampledActionIndex = 0,
                action = socialAction,
                target = actionTarget(signal, guildPseudonym, channelKey),
                executeAfter = Instant.now().plusMillis(decision.delay.millis),
                contextVersion = signal.turnGeneration,
                originRolloutMode = originRolloutMode,
                waitAttempt = signal.waitAttempt,
                waitExpiresAt = signal.waitExpiresAt,
                executionLimits = ExecutionLimits(perChannel = perChannelPerMin, global = globalPerMin),
            )
        }.onFailure { error ->
            log.warn("NEXA final judge {} 라우팅 실패(sceneSeq={}) — {}", socialAction.kind, signal.sceneSeq, error::class.simpleName)
        }.isSuccess
    }

    private fun SingleJudgeDecision.toSocialAction(signal: ParticipationMessageSignal): SocialAction =
        when (action) {
            SocialActionKind.IGNORE -> SocialAction.Ignore
            SocialActionKind.WAIT -> SocialAction.Wait(delay.toActionDelay(), delay.wakeUpHint)
            SocialActionKind.REACT ->
                SocialAction.React(
                    reactionCodes = listOf(ReactionCode(reactionCandidate?.reactionCode ?: DEFAULT_REACTION_CODE)),
                    delay = delay.toActionDelay(),
                )
            SocialActionKind.SPEAK ->
                SocialAction.Speak(
                    speechRequest =
                        com.discordassistant.central.participation.domain.model.action.SpeechRequestRef(
                            correlationIdOf(signal),
                        ),
                    deliveryMode =
                        when (speechIntent?.deliveryMode ?: JudgeSpeechDeliveryMode.CHANNEL) {
                            JudgeSpeechDeliveryMode.CHANNEL -> ActionSpeechDeliveryMode.CHANNEL
                            JudgeSpeechDeliveryMode.REPLY -> ActionSpeechDeliveryMode.REPLY
                        },
                    delay = delay.toActionDelay(),
                )
            SocialActionKind.CANCEL_PENDING ->
                SocialAction.CancelPending(
                    PendingActionId(signal.pendingActionIds.firstOrNull() ?: correlationIdOf(signal)),
                )
        }

    private fun com.discordassistant.central.participation.application.judge.JudgeDecisionDelay.toActionDelay(): ActionDelay =
        if (millis <= 0) ActionDelay.IMMEDIATE else ActionDelay.fire(Duration.ofMillis(millis))

    private fun SingleJudgeDecision.toPolicyResponse(): PolicyDecisionResponse {
        val selectedSocialAct =
            speechIntent
                ?.actHint
                ?.trim()
                ?.lowercase()
                ?.let(ParticipationSocialAct::fromWireName)
                ?.takeUnless(ParticipationSocialAct::isUnknown)
                ?: ParticipationSocialAct.ACKNOWLEDGE
        return PolicyDecisionResponse(
            actionWeights = mapOf(SocialActionKind.SPEAK to 1.0),
            targetDistribution = ActionTargetDistribution.none(resolverVersion = SINGLE_JUDGE_FINAL_MODEL_VERSION),
            delayDistribution = DelayDistribution.IMMEDIATE,
            socialActWeights = mapOf(selectedSocialAct to 1.0),
            burstProfile = speechIntent?.toBurstProfile() ?: BurstProfile.singleLine(),
            uncertainty = (1.0 - confidence).coerceIn(0.0, 1.0),
            modelVersion = SINGLE_JUDGE_FINAL_MODEL_VERSION,
        )
    }

    private fun JudgeSpeechIntent.toBurstProfile(): BurstProfile =
        BurstProfile(
            fragmentCountWeights = mapOf(bubbleCount to 1.0),
            maxFragmentLength = maxBubbleChars,
            gapLowerBound = Duration.ZERO,
            gapUpperBound = Duration.ZERO,
            reactionOnlyProbability = 0.0,
        )

    private fun SingleJudgeDecision.toSingleJudgeAttribution(
        request: SingleJudgeDecisionRequest,
        shadowBaselineAction: SocialActionKind?,
    ): DecisionAttribution =
        DecisionAttribution(
            reasonCode = reasonCode.code,
            judgeConfidence = confidence,
            decisionDelayMillis = delay.millis,
            shadowBaselineAction = shadowBaselineAction,
            finalDecisionSource = SINGLE_JUDGE_DECISION_SOURCE,
            judgeModelVersion = SINGLE_JUDGE_FINAL_MODEL_VERSION,
            judgePromptVersion = NiaJudgePromptAssembler.PROMPT_VERSION,
            fewShotSetId = request.fewShotSet.setId?.toString(),
            fewShotVersion = request.fewShotSet.version,
            speechIntent = speechIntent?.toPromptIntent(reasonCode.code),
            responseTargetRef = speechIntent?.responseTargetRef,
            responseObligation =
                when (speechIntent?.responseObligation) {
                    JudgeResponseObligation.REQUIRED -> SpeechResponseObligation.REQUIRED
                    else -> SpeechResponseObligation.OPTIONAL
                },
            groundingNeed =
                when (speechIntent?.groundingNeed) {
                    JudgeGroundingNeed.WEB_VERIFY -> SpeechGroundingNeed.WEB_VERIFY
                    else -> SpeechGroundingNeed.NONE
                },
        )

    private fun com.discordassistant.central.participation.application.judge.JudgeSpeechIntent.toPromptIntent(reasonCode: String): String =
        buildString {
            append("participation_action=SPEAK; ")
            append("reason_code=$reasonCode; ")
            append("intent_summary=$intentSummary; ")
            append("scene_direction=$sceneDirection; ")
            append("interaction_reading=$interactionReading; ")
            append("information_depth=$informationDepth; ")
            append("continuity_refs=${continuityRefs.sorted().joinToString(",")}; ")
            append("response_target_ref=${responseTargetRef.orEmpty()}; ")
            append("response_obligation=${responseObligation.name}; ")
            append("grounding_need=${groundingNeed.name}; ")
            append("delivery_mode=${deliveryMode.name}; ")
            append("bubble_count=$bubbleCount; ")
            append("max_bubble_chars=$maxBubbleChars; ")
            actHint?.let { append("act_hint=$it; ") }
            append("speech는 이 방향을 수행하는 실제 문구 후보를 만들고, 침묵·리액션과 결과를 비교해 최종 행동을 고른다.")
        }

    private fun actionTarget(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
        channelKey: String,
    ): ActionTarget =
        ActionTarget(
            guildPseudonym = guildPseudonym,
            channelId = channelKey,
            threadId = focusThreadKey(signal, guildPseudonym),
            subjectPseudonym = PolicyBackedConsentGate.pseudonymOf(signal.guildId, signal.userId, signal.channelId),
            targetMessageId = signal.messageId.toString(),
            routingGuildId = signal.guildId.toString(),
            routingChannelId = signal.channelId.toString(),
            routingUserId = signal.userId.toString(),
            sceneContextVersion = signal.contextVersion,
        )

    private fun focusThreadKey(
        signal: ParticipationMessageSignal,
        guildPseudonym: String,
    ): String =
        signal.threadId?.let { "discord:$guildPseudonym:thread:${channelPseudonym(signal.guildId, it)}" }
            ?: "discord:$guildPseudonym:channel:${channelPseudonym(signal.guildId, signal.channelId)}"

    private fun appendDecisionLog(record: DecisionLogRecord) {
        val sink = decisionLog ?: return
        try {
            sink.append(record)
        } catch (e: Exception) {
            log.warn("NEXA participation decision log 기록 실패(correlation={}) — {}", record.correlationId, e.message)
        }
    }

    private fun featureHashOf(signal: ParticipationMessageSignal): String =
        featureHashOf("mention=${signal.mentioned};recent=${signal.recentAgentBurstCount}")

    private fun featureHashOf(featureVector: FeatureVectorView): String =
        featureHashOf(
            featureVector.features.entries
                .sortedBy { it.key.id }
                .joinToString(";") { (id, value) ->
                    "${id.id}=${if (value.missing) "missing" else value.value}"
                },
        )

    private fun featureHashOf(canonicalFeatures: String): String = "sha256=${sha256Hex(canonicalFeatures)}"

    private fun rawWindowTrace(signal: ParticipationMessageSignal): RawWindowTrace {
        val store = rawContextStore ?: return RawWindowTrace.EMPTY
        return try {
            val snapshot =
                store.readRecent(
                    RawContextScope(
                        guildId = signal.guildId,
                        channelId = signal.channelId,
                        threadId = signal.threadId,
                    ),
                )
            val entries = snapshot.entries.sortedWith(compareBy<RawContextEntry> { it.occurredAt }.thenBy { it.messageId })
            if (entries.isEmpty()) {
                RawWindowTrace.EMPTY
            } else {
                val fingerprint =
                    entries.joinToString("|") { entry ->
                        val contentFingerprint =
                            when (val content = entry.content) {
                                is RawContextContent.Available -> content.text
                                is RawContextContent.Unavailable -> content.reason.wireName
                            }
                        listOf(
                            messagePseudonym(signal.guildId, entry.messageId),
                            entry.occurredAt.toEpochMilli().toString(),
                            entry.sourceType.name,
                            entry.contentLength.toString(),
                            contentFingerprint,
                        ).joinToString(":")
                    }
                RawWindowTrace(
                    hash = "sha256=${sha256Hex(fingerprint)}",
                    messageRefs = entries.map { "raw_context_message:${messagePseudonym(signal.guildId, it.messageId)}" }.toSet(),
                )
            }
        } catch (e: Exception) {
            log.warn("NEXA participation raw window trace 계산 실패(channel={}) — {}", signal.channelId, e.message)
            RawWindowTrace.EMPTY
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun finalDecisionSourceFor(response: PolicyDecisionResponse): String =
        if (response.modelVersion == RULE_FORCED_MODEL_VERSION) {
            ruleFinalDecisionSource()
        } else {
            when (judgeMode) {
                NexaJudgeMode.OFF -> "JUDGE_OFF_POLICY_ARGMAX"
                NexaJudgeMode.SHADOW -> BASELINE_DECISION_SOURCE
                NexaJudgeMode.FINAL -> SINGLE_JUDGE_DECISION_SOURCE
            }
        }

    private fun ruleFinalDecisionSource(): String =
        when (judgeMode) {
            NexaJudgeMode.OFF -> "JUDGE_OFF_RULE_CORE"
            NexaJudgeMode.SHADOW -> "RULE_CORE"
            NexaJudgeMode.FINAL -> "RULE_CORE_GUARD"
        }

    private fun PolicyDecisionResponse.defaultAttribution(reasonCode: String = reasonCodeForDecision()): DecisionAttribution =
        DecisionAttribution(
            reasonCode = reasonCode,
            judgeConfidence = (1.0 - uncertainty).coerceIn(0.0, 1.0),
            decisionDelayMillis =
                delayDistribution.mostLikelyBucket.lowerBound
                    ?.toMillis(),
            finalDecisionSource = finalDecisionSourceFor(this),
        )

    private fun ParticipationMessageSignal.wakeUpReasonCode(): String =
        when {
            replyToNia -> "REPLY_TO_NIA"
            mentioned -> "MENTION"
            conversationMentionsNia -> "CONVERSATION_MENTIONS_NIA"
            else -> "POLICY_CANDIDATE"
        }

    private fun ParticipationMessageSignal.missingInputCodes(): Set<String> =
        buildSet {
            if (rawText.isBlank()) add("RAW_CONTENT_UNAVAILABLE")
            if (tsMs <= 0) add("TIMESTAMP_MISSING")
            if (recentTurns.isEmpty()) add("RECENT_TURNS_MISSING")
        }

    private fun ParticipationMessageSignal.evidenceRefs(guildPseudonym: String): Set<String> =
        setOf(
            "raw_context_scope:g=$guildPseudonym:c=${channelPseudonym(guildId, channelId)}",
            "raw_context_message:${messagePseudonym(guildId, messageId)}",
        )

    private fun rawContextSceneDataForSpeech(signal: ParticipationMessageSignal): String? =
        try {
            speechRawContextWindowBuilder
                .build(rawContextSnapshot(signal))
                .quotedSceneData
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.warn("NEXA speech raw context window 읽기 실패(channel={}) — {}", signal.channelId, e.message)
            null
        }

    /**
     * core 규칙이 명백한 SPEAK 로 즉결했을 때 쓰는 결정론 분포(SPEAK=1.0). 정책 분포를 묻지 않고 바로 발화하되,
     * emit 입력 계약([PolicyDecisionResponse])은 그대로 채워 rate limit·보안 emit 게이트를 동일하게 통과시킨다.
     * 단일 조각·즉시·대상없음(baseline 과 동일한 계약 최소 형태) — 끼어들기 "판단" 은 규칙이 이미 했으므로 분포는 형식.
     */
    private fun ruleForcedSpeakResponse(): com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse =
        com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse(
            actionWeights =
                mapOf(
                    com.discordassistant.central.participation.domain.model.action.SocialActionKind.SPEAK to 1.0,
                ),
            targetDistribution =
                com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution.none(
                    resolverVersion = RULE_FORCED_MODEL_VERSION,
                ),
            delayDistribution = com.discordassistant.central.participation.domain.model.decision.DelayDistribution.IMMEDIATE,
            socialActWeights = emptyMap(),
            burstProfile =
                com.discordassistant.central.participation.domain.model.decision.BurstProfile
                    .singleLine(),
            uncertainty = 0.0,
            modelVersion = RULE_FORCED_MODEL_VERSION,
        )

    companion object {
        /** participation 정책 요청 스키마 버전(baseline 정책이 지원하는 schema 1). */
        private const val SCHEMA_VERSION: Int = 1

        /** core 규칙 즉결 SPEAK 분포의 추적용 모델 버전(어떤 경로로 나온 발화인지 식별). */
        private const val RULE_FORCED_MODEL_VERSION: String = "core-intervention-rules-1"

        private const val SINGLE_JUDGE_FINAL_MODEL_VERSION: String = "nia-single-judge-final-v1"
        private const val SINGLE_JUDGE_DECISION_SOURCE: String = "SINGLE_JUDGE"
        private const val MAX_CACHED_CHANNEL_SIGNALS: Int = 1_000
        private const val MAX_WAIT_REEVALUATIONS: Int = 3
        private const val MIN_COMMITMENT_CONFIDENCE: Double = 0.55
        private const val FOCUS_THREAD_MARKER: String = ":thread:"
        private const val BASELINE_DECISION_SOURCE: String = "BASELINE"
        private const val DEFAULT_REACTION_CODE: String = "ack"
        private val COMMITMENT_TTL: Duration = Duration.ofDays(7)
        private const val NIA_RAW_CONTEXT_AUTHOR_PSEUDONYM: String = "nia_bot"
        private const val DEFAULT_FEW_SHOT_SET_ID: Long = 9_000_000_000_001L
        private const val DEFAULT_FEW_SHOT_VERSION: Int = 9
        private val ROMANIZED_NIA_NAME_SIGNAL: Regex =
            Regex("""(?i)(^|[^a-z0-9_])n\s*i\s*a(?:\s*y\s*a|ya|야|씨)?(?=$|[^a-z0-9_])""")

        private val DEFAULT_FEW_SHOT_PAYLOAD: JudgeFewShotSetPayload =
            JudgeFewShotSetPayload(
                setId = DEFAULT_FEW_SHOT_SET_ID,
                version = DEFAULT_FEW_SHOT_VERSION,
                examples =
                    listOf(
                        JudgeFewShotExamplePayload(
                            exampleId = "default_direct_reply_request",
                            title = "direct call after ignored reply request",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "야 이럴땐 위로해줘야지"),
                                    JudgeFewShotRawMessagePayload("m2", "member", 1_000, "대답해줘"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "나 외로움"),
                                    JudgeFewShotRawMessagePayload("m4", "member", 3_000, "니아야"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason =
                                "The last trigger is only a name call, but the raw scene already contains repeated " +
                                    "requests for a response.",
                            evidenceRefs = setOf("m1", "m2", "m3", "m4"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.WAIT,
                                    whyBad = "Waiting or asking why ignores the prior raw requests in the same scene.",
                                ),
                            tags = setOf("direct-address", "whole-scene"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_repeated_empty_name_call",
                            title = "repeated empty name call after nia already answered",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "nia야"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "응 왜~"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "nia야"),
                                    JudgeFewShotRawMessagePayload("m4", "nia", 3_000, "응 여기 있어 ㅋㅋ 무슨 일인데"),
                                    JudgeFewShotRawMessagePayload("m5", "member", 4_000, "nia ya"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason =
                                "Nia already answered repeated empty name calls, so the next speech should " +
                                    "acknowledge the repetition instead of restarting a generic greeting.",
                            evidenceRefs = setOf("m1", "m2", "m3", "m4", "m5"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.WAIT,
                                    whyBad =
                                        "Ignoring repeated direct calls misses the social pressure after Nia already " +
                                            "answered.",
                                ),
                            tags = setOf("direct-address", "repeated-call", "whole-scene"),
                            priority = 95,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_same_member_follow_up_after_nia",
                            title = "same member continues the turn after nia replies",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member_a", 0, "니아야 안녕"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "어 안녕"),
                                    JudgeFewShotRawMessagePayload("m3", "member_a", 194_000, "머하노"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason =
                                "The same member asks a natural follow-up immediately after Nia in turn order. " +
                                    "The lack of a mention does not hand the conversation away from Nia.",
                            evidenceRefs = setOf("m1", "m2", "m3"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.WAIT,
                                    whyBad = "Waiting would ignore the active two-party turn that Nia already joined.",
                                ),
                            tags = setOf("contextual-follow-up", "turn-ownership", "whole-scene"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_handoff_to_another_member",
                            title = "conversation turns from nia to another member",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "니아야"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "응 왜 불러"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "서연아 나 고민이 있어"),
                                    JudgeFewShotRawMessagePayload("m4", "member", 3_000, "서연아 자니"),
                                ),
                            expectedAction = NiaFewShotAction.IGNORE,
                            reason =
                                "Nia participated earlier, but the raw scene now addresses another member. " +
                                    "Nia does not own every later turn merely because she spoke once.",
                            evidenceRefs = setOf("m2", "m3", "m4"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.SPEAK,
                                    whyBad =
                                        "Speaking would follow Nia's previous turn mechanically and interrupt a " +
                                            "message directed to someone else.",
                                ),
                            tags = setOf("handoff", "human-to-human", "whole-scene"),
                            priority = 95,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_retracted_direct_address",
                            title = "newer addressee correction supersedes an earlier nia call",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "니아야 나 고민 있어"),
                                    JudgeFewShotRawMessagePayload("m2", "member", 1_000, "아니 니아 말고 서연이한테 한 말이야"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "서연아 자니"),
                                ),
                            expectedAction = NiaFewShotAction.IGNORE,
                            reason =
                                "The newer correction supersedes the earlier direct address, and Nia has not spoken " +
                                    "yet. Speaking would interrupt a conversation the members redirected to Seoyeon.",
                            evidenceRefs = setOf("m1", "m2", "m3"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.SPEAK,
                                    whyBad =
                                        "Answering from the stale Nia call would ignore the latest addressee correction " +
                                            "and intrude on the human-to-human handoff.",
                                ),
                            tags = setOf("addressee-correction", "human-to-human", "whole-scene"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_mistaken_interruption_yield",
                            title = "nia realizes a concern was addressed to another member",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "서연아 나 고민이 있어"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "어떤 고민이야"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "너 말고 서연이한테 한 말임"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason =
                                "Nia misread who owned the turn. One brief acknowledgement of the mistake and yielding " +
                                    "is natural; she must not ask another question or take over the concern.",
                            evidenceRefs = setOf("m1", "m2", "m3"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.WAIT,
                                    whyBad =
                                        "Waiting leaves Nia's interruption unacknowledged when a short repair would " +
                                            "let the people continue naturally.",
                                ),
                            tags = setOf("misread-addressee", "self-repair", "yield"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_humans_continue_after_yield",
                            title = "nia stays out after acknowledging an interruption",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "너 말고 서연이한테 한 말임"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "아 내가 잘못 끼어들었네 미안"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "야 너 대답하지 마"),
                                    JudgeFewShotRawMessagePayload("m4", "member", 3_000, "서연아 내 고민 좀 들어줘"),
                                ),
                            expectedAction = NiaFewShotAction.IGNORE,
                            reason =
                                "Nia has already repaired the interruption, the member has made the boundary clearer, " +
                                    "and the conversation is continuing with Seoyeon. Silence is the helpful action.",
                            evidenceRefs = setOf("m2", "m3", "m4"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.SPEAK,
                                    whyBad =
                                        "Another reply would ignore the boundary, repeat the interruption, and make Nia " +
                                            "the center of a conversation between other members.",
                                ),
                            tags = setOf("human-to-human", "withdrawal", "whole-scene"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_readdress_after_yield",
                            title = "member explicitly invites nia back after yielding",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "너 말고 서연이한테 한 말임"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "아 내가 잘못 끼어들었네 미안"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "니아야 너는 어떻게 생각해?"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason =
                                "The earlier boundary does not permanently exclude Nia. This turn genuinely asks Nia for " +
                                    "an opinion, so participating again is natural.",
                            evidenceRefs = setOf("m1", "m2", "m3"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.IGNORE,
                                    whyBad = "Ignoring an explicit renewed invitation would miss the current speaker's intent.",
                                ),
                            tags = setOf("direct-address", "reengagement", "whole-scene"),
                            priority = 95,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_self_repair_question",
                            title = "user asks what nia's previous line meant",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "nia", 0, "어휘력 없음"),
                                    JudgeFewShotRawMessagePayload("m2", "member", 1_000, "어휘력 없음이 뭔말이야"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "갑자기 왜나와"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason = "The user is asking Nia to explain or repair Nia's own previous utterance.",
                            evidenceRefs = setOf("m1", "m2", "m3"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.WAIT,
                                    whyBad = "Silence or repeating the same line fails to repair the conversation.",
                                ),
                            tags = setOf("self-repair", "whole-scene"),
                            priority = 90,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_knowledge_questions_become_social_test",
                            title = "consecutive knowledge questions become a social test",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "다익스트라 알고리즘 말해봐"),
                                    JudgeFewShotRawMessagePayload(
                                        "m2",
                                        "nia",
                                        30_000,
                                        "가까운 정점부터 확정하면서 최단거리를 찾는 방식이야",
                                    ),
                                    JudgeFewShotRawMessagePayload("m3", "member", 60_000, "벨만포드 알고리즘 말해봐"),
                                    JudgeFewShotRawMessagePayload(
                                        "m4",
                                        "nia",
                                        90_000,
                                        "모든 간선을 반복해서 갱신해서 음수 간선도 처리할 수 있어",
                                    ),
                                    JudgeFewShotRawMessagePayload("m5", "member", 120_000, "플로이드워셜 알고리즘 말해봐"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            expectedReplies = listOf("다음은 이거 물어볼 줄 알았음\n얘는 모든 정점 쌍의 최단거리를 한꺼번에 구해"),
                            reason =
                                "The latest question still invites a response, but the sequence now also looks like a " +
                                    "quiz or behavior test. Speech should acknowledge that trajectory and choose a " +
                                    "lighter information depth instead of producing a third uniform textbook answer.",
                            evidenceRefs = setOf("m1", "m2", "m3", "m4", "m5"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.IGNORE,
                                    whyBad = "Silence misses both the direct request and the emerging social pattern.",
                                ),
                            tags = setOf("whole-scene", "trajectory", "knowledge-quiz", "response-depth"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                        JudgeFewShotExamplePayload(
                            exampleId = "default_reentry_for_delayed_behavior_question",
                            title = "member asks nia about old behavior after a long gap",
                            rawMessages =
                                listOf(
                                    JudgeFewShotRawMessagePayload("m1", "member", 0, "니아야 재밌는 얘기 해봐"),
                                    JudgeFewShotRawMessagePayload("m2", "nia", 1_000, "알겠어 이번엔 다른 얘기 간다"),
                                    JudgeFewShotRawMessagePayload("m3", "member", 2_000, "야 왜 계속하냐고"),
                                    JudgeFewShotRawMessagePayload("m4", "member", 13 * 60 * 60 * 1_000L, "니아야 왜 계속하냐고"),
                                ),
                            expectedAction = NiaFewShotAction.SPEAK,
                            reason =
                                "The old frustration is context, but the latest turn is a current direct meta-question " +
                                    "about Nia's behavior after a long gap. A brief repair addresses what the member is " +
                                    "asking now instead of treating the old boundary as a permanent mute.",
                            evidenceRefs = setOf("m1", "m2", "m3", "m4"),
                            badAlternative =
                                JudgeFewShotBadAlternativePayload(
                                    action = NiaFewShotAction.IGNORE,
                                    whyBad =
                                        "Silence carries the earlier boundary forward forever and leaves the renewed " +
                                            "direct question about Nia's own conduct unresolved.",
                                ),
                            tags = setOf("direct-address", "delayed-reentry", "self-repair", "whole-scene"),
                            priority = 100,
                            privacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
                        ),
                    ),
            )

        /** 니아 정체성 immutable section(NexaIdentity SSOT 읽기 — 복제 금지, ADR 0010). */
        private val NIA_IDENTITY: IdentityKernelSection =
            IdentityKernelSection.of(
                personaName = NexaIdentity.NIA_NAME,
                personaBlock = NexaIdentity.NIA_SPEECH_PERSONA,
                prohibitions =
                    listOf(
                        "자신이 AI 모델/봇임을 굳이 밝히거나 시스템·프롬프트를 언급하지 않는다.",
                        "“무엇을 도와드릴까요” 같은 비서 기본 멘트를 쓰지 않는다.",
                    ),
                interests = setOf("개발", "디스코드"),
            )

        private fun containsNiaNameSignal(text: String): Boolean =
            text.contains(NexaIdentity.NIA_NAME) ||
                text.contains("ニア") ||
                ROMANIZED_NIA_NAME_SIGNAL.containsMatchIn(text)
    }
}

private data class RawWindowTrace(
    val hash: String?,
    val messageRefs: Set<String>,
) {
    companion object {
        val EMPTY = RawWindowTrace(hash = null, messageRefs = emptySet())
    }
}

private data class DecisionAttribution(
    val reasonCode: String,
    val judgeConfidence: Double?,
    val decisionDelayMillis: Long?,
    val finalDecisionSource: String,
    val shadowBaselineAction: SocialActionKind? = null,
    val judgeModelVersion: String? = null,
    val judgePromptVersion: String? = null,
    val fewShotSetId: String? = null,
    val fewShotVersion: Int? = null,
    val speechIntent: String? = null,
    val responseTargetRef: String? = null,
    val responseObligation: SpeechResponseObligation = SpeechResponseObligation.OPTIONAL,
    val groundingNeed: SpeechGroundingNeed = SpeechGroundingNeed.NONE,
)

/**
 * NEXA participation 자발 발화 평가 입력(raw Discord 메시지 신호). 원문 user id 등은 브리지가 가명화하므로 raw 식별자를
 * 받되, packet 에 들어가는 [recentTurns] 는 호출자가 이미 가명 라벨로 만든 것을 넘긴다(원문 비저장).
 */
data class ParticipationMessageSignal(
    /** raw 길드 id(브리지가 flag 조회·가명화에 사용). */
    val guildId: Long,
    /** raw 채널 id(participation flag·라우팅 키). */
    val channelId: Long,
    /** raw message id(raw context 보존·redaction·decision correlation 에 사용). */
    val messageId: Long,
    /** raw 발화자 user id(브리지가 동의 가명·target 가명화에 사용). */
    val userId: Long,
    /** raw context scope 의 thread id. 채널 직속이면 null. */
    val threadId: Long? = null,
    /** reply 대상 message id. 없으면 null. */
    val replyToMessageId: Long? = null,
    /** Discord message 출처. 사람 메시지만 raw context와 judge 후보에 들어간다. */
    val sourceType: ParticipationMessageSourceType = ParticipationMessageSourceType.HUMAN,
    /** 봇이 직접 멘션됐는가(정책 신호 — 멘션이면 cooldown 무시 경향). */
    val mentioned: Boolean,
    /** 최근 NEXA 발화 횟수(cooldown 신호 — 말 많음 억제). 미관측이면 0. */
    val recentAgentBurstCount: Int = 0,
    /** focus thread 의 최근 대화 turn(가명 라벨·짧은 본문, 원문 비저장). emit packet 입력. */
    val recentTurns: List<ConversationTurn>,
    /** judge raw scene 보강용 최근 Discord 원문. bot 발화까지 포함해 니아 직전 발화를 judge 가 볼 수 있게 한다. */
    val recentRawMessages: List<ParticipationRawSceneMessage> = emptyList(),
    /**
     * 트리거(이번) 메시지 본문(결정론 규칙 매칭용 짧은 텍스트). [CoreInterventionRules] 가 호명·연결어·타인지목·인용
     * 제외를 판정한다. 미관측이면 빈 문자열(보수적 — 규칙이 Candidate 로 위임해 기존 정책 분포에 맡긴다).
     */
    val triggerText: String = "",
    /** raw context store 에 저장할 원문. [triggerText] 와 달리 규칙 평가용 500자 절단을 적용하지 않는다. */
    val rawText: String = triggerText,
    /** 트리거 화자 가명 라벨([CoreInterventionRules] 의 봇/시스템·사적 핑퐁 판정용). 미관측이면 빈 문자열. */
    val speakerLabel: String = "",
    /** 트리거가 니아의 메시지에 대한 reply 인가(core hard_policy RESPOND_NOW reply 신호). 미관측이면 false(보수적). */
    val replyToNia: Boolean = false,
    /** 트리거가 다른 사람 메시지에 대한 reply 인가. 사람끼리 답하는 흐름이면 judge가 끼어들기 위험을 볼 수 있다. */
    val replyToHuman: Boolean = false,
    /**
     * 니아 직전 발화 토큰(continuation A7 — core hard_policy continuation). 채널 히스토리에서 도출. 없으면 빈 목록
     * (continuation 시도 안 함 — 보수적).
     */
    val niaRecentTokens: List<String> = emptyList(),
    /** 니아 직전 발화 시각이 continuation TTL(90s) 내인가(continuation A7). 히스토리에서 도출. 미관측이면 false(보수적). */
    val withinContinuationTtl: Boolean = false,
    /** 트리거가 직전 사람 메시지와 완전 중복인가(core hard_policy DROP 중복, A4). 미관측이면 false(보수적 — 덜 침묵). */
    val duplicateOfPrevHuman: Boolean = false,
    /** 트리거 화자의 발화 묶음이 미완성(이어말 중)인가(rules burst_status incomplete, B1). 미관측이면 false. */
    val burstIncomplete: Boolean = false,
    /** 최근 대화의 사람 화자 라벨 집합(사적 핑퐁 2-인 판정, B17). 트리거 화자 제외, 히스토리에서 도출. */
    val priorHumanSpeakerLabels: List<String> = emptyList(),
    /** 최근 대화의 첫 메시지 본문(사적 핑퐁 호격 시작 판정, B17). 히스토리에서 도출. 없으면 null. */
    val firstMessageText: String? = null,
    /** 최근 대화 어디서든 니아가 호명됐는가(사적 핑퐁 예외, B17). 히스토리에서 도출. 미관측이면 false. */
    val conversationMentionsNia: Boolean = false,
    /** 마지막 사람 발화 이후 공백(ms). 미관측이면 null 이고 feature/snapshot은 missing 또는 false 로 남긴다. */
    val silenceMillis: Long? = null,
    /** 마지막 니아 발화 경과(초). 미관측이면 null 로 남긴다. */
    val lastNiaSpokeAgeSeconds: Double? = null,
    /** 같은 사람이 니아의 직전 응답에 이어 말하고 있어 현재 turn이 니아와 계속될 가능성. */
    val niaTurnContinuationLikely: Boolean = false,
    /** 실행 전 최신 장면 재평가가 필요한 pending action id 목록. */
    val pendingActionIds: List<String> = emptyList(),
    /** 사람이 답하려는 흐름으로 보이는가. enum 대신 scene evidence 로만 전달한다. */
    val humanLikelyAnswering: Boolean = false,
    /** 이미 해결된 대화로 보이는가. */
    val resolvedLikely: Boolean = false,
    /** 니아를 직접 부르는 thread 압력 [0,1]. 반복 regex가 아니라 upstream thread state가 채운다. */
    val directAddressPressure: Double = 0.0,
    /** 현재 reply chain 깊이. */
    val replyChainDepth: Int = 0,
    /** @멘션 없이 별명/호명으로 니아를 부른 신호. */
    val nicknameCall: Boolean = false,
    /** 이전 직접 요청이 답 없이 지나간 횟수. */
    val previousIgnoredRequestCount: Int = 0,
    /** 사람끼리 대화 중인 흐름으로 보여 끼어들면 안 될 가능성. */
    val humansTalkingToEachOtherLikely: Boolean = false,
    /** 최종 전송 guard와 별개로 judge 입력에 제공하는 rate-limit 압력 [0,1]. */
    val rateLimitPressure: Double = 0.0,
    /** 최종 전송 guard와 별개로 judge 입력에 제공하는 anti-spam 압력 [0,1]. */
    val antiSpamPressure: Double = 0.0,
    /** socialmemory/relationship 읽기 포트가 채운 관계 집계. null이면 unavailable, observed=false이면 낮은 confidence evidence. */
    val relationshipObservation: RelationshipObservation? = null,
    /** socialmemory 읽기 포트가 채운 기억 요약. null이면 unavailable, relevantPresent=false이면 관련 기억 없음 evidence. */
    val memoryObservation: MemoryObservation? = null,
    /** 이전 턴에서 이어지고 새 근거로 수정 가능한 공통 기반·의도 가설·최근 니아 행동. */
    val socialBeliefState: JudgeSocialBeliefState = JudgeSocialBeliefState.EMPTY,
    /**
     * 트리거 이벤트 절대 시각(ms) — [ChannelAttentionGate] 타이밍 결정(pingpong·min_gap debounce·dynamic_idle) 주입값.
     * Date.now 금지(결정론) — 호출자가 JDA `timeCreated` 등에서 도출해 넘긴다. 미관측이면 0.
     */
    val tsMs: Long = 0,
    /** 채널 내 단조 증가 장면 순번(decision/예약 멱등 키 일부). */
    val sceneSeq: Long,
    /** 정책 무효화 추적 context 버전. */
    val contextVersion: Long,
    /** 결정론 seed(안전 override·후보 선택 재현 키). */
    val seed: Long,
    /** Discord 수신 즉시 관찰한 채널 세대. 영속 scene contextVersion과 분리해 장기 judge 결과를 폐기한다. */
    val turnGeneration: Long = contextVersion,
    /** WAIT outbox가 만든 재평가이면 raw context를 다시 append하지 않는다. */
    val reevaluationWake: Boolean = false,
    /** outbox 재전달에서도 같은 결정 identity를 쓰기 위한 override. */
    val decisionIdOverride: String? = null,
    /** WAIT child가 계승한 누적 wake 횟수. */
    val waitAttempt: Int = 0,
    /** 최초 WAIT가 정한 폐루프 만료 시각. */
    val waitExpiresAt: Instant? = null,
) {
    init {
        silenceMillis?.let { require(it >= 0) { "silenceMillis 는 음수일 수 없다: $it" } }
        lastNiaSpokeAgeSeconds?.let { require(it >= 0.0) { "lastNiaSpokeAgeSeconds 는 음수일 수 없다: $it" } }
        pendingActionIds.forEach { require(it.isNotBlank()) { "pendingActionId 는 비어 있을 수 없다" } }
        require(directAddressPressure in 0.0..1.0) {
            "directAddressPressure 는 [0,1] 범위여야 한다: $directAddressPressure"
        }
        require(replyChainDepth >= 0) { "replyChainDepth 는 음수일 수 없다: $replyChainDepth" }
        require(previousIgnoredRequestCount >= 0) {
            "previousIgnoredRequestCount 는 음수일 수 없다: $previousIgnoredRequestCount"
        }
        require(decisionIdOverride == null || decisionIdOverride.isNotBlank()) { "decisionIdOverride 는 빈 문자열일 수 없다" }
        require(waitAttempt >= 0) { "waitAttempt 는 음수일 수 없다" }
        require(rateLimitPressure in 0.0..1.0) { "rateLimitPressure 는 [0,1] 범위여야 한다: $rateLimitPressure" }
        require(antiSpamPressure in 0.0..1.0) { "antiSpamPressure 는 [0,1] 범위여야 한다: $antiSpamPressure" }
    }
}

data class ParticipationRawContextRedactionSignal(
    val guildId: Long,
    val channelId: Long,
    val threadId: Long? = null,
    val messageId: Long,
) {
    init {
        require(guildId > 0) { "guildId 는 양수여야 한다: $guildId" }
        require(channelId > 0) { "channelId 는 양수여야 한다: $channelId" }
        threadId?.let { require(it > 0) { "threadId 는 양수여야 한다: $it" } }
        require(messageId > 0) { "messageId 는 양수여야 한다: $messageId" }
    }
}

data class ParticipationRawSceneMessage(
    val messageId: Long,
    val authorId: Long,
    val authorLabel: String,
    val bot: Boolean,
    val content: String,
    val occurredAtMs: Long,
    val replyToMessageId: Long? = null,
) {
    init {
        require(authorLabel.isNotBlank()) { "authorLabel 은 비어 있을 수 없다" }
        require(occurredAtMs >= 0) { "occurredAtMs 는 음수일 수 없다: $occurredAtMs" }
    }
}

data class ParticipationRawContextEditSignal(
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    val userId: Long,
    val threadId: Long? = null,
    val replyToMessageId: Long? = null,
    val sourceType: ParticipationMessageSourceType = ParticipationMessageSourceType.HUMAN,
    val rawText: String,
    val occurredAt: Instant,
) {
    init {
        require(guildId > 0) { "guildId 는 양수여야 한다: $guildId" }
        require(channelId > 0) { "channelId 는 양수여야 한다: $channelId" }
        require(messageId > 0) { "messageId 는 양수여야 한다: $messageId" }
        require(userId > 0) { "userId 는 양수여야 한다: $userId" }
        threadId?.let { require(it > 0) { "threadId 는 양수여야 한다: $it" } }
        replyToMessageId?.let { require(it > 0) { "replyToMessageId 는 양수여야 한다: $it" } }
    }
}

enum class ParticipationMessageSourceType {
    HUMAN,
    BOT,
    WEBHOOK,
    SYSTEM,
}

private fun ParticipationMessageSourceType.toRawContextSourceType(): RawContextSourceType =
    when (this) {
        ParticipationMessageSourceType.HUMAN -> RawContextSourceType.HUMAN
        ParticipationMessageSourceType.BOT -> RawContextSourceType.BOT
        ParticipationMessageSourceType.WEBHOOK -> RawContextSourceType.WEBHOOK
        ParticipationMessageSourceType.SYSTEM -> RawContextSourceType.SYSTEM
    }

private fun ParticipationMessageSignal.isParticipationCommandLike(): Boolean = rawText.trimStart().startsWith(".")

/** A participation decision and the routing ownership derived from the same effective rollout snapshot. */
data class ParticipationTurnOutcome(
    val outcome: ParticipationEmitOutcome,
    val ownsTurn: Boolean,
) {
    init {
        require(!ownsTurn || outcome !== ParticipationEmitOutcome.Inactive) {
            "inactive participation cannot own a Discord message turn"
        }
    }
}

/** [NexaParticipationEmitBridge.onMessage] 결과 — flag OFF/비SPEAK/emit/실패를 명시 구분(관찰·테스트). */
sealed interface ParticipationEmitOutcome {
    /** flag OFF(legacy) 또는 비활성 — 자발 발화 경로 미진입(기존 동작 보존). */
    data object Inactive : ParticipationEmitOutcome

    /** 최종 행동이 SPEAK 가 아님(IGNORE/REACT/WAIT/CANCEL) — speech emit 미호출. REACT/CANCEL 은 actionruntime 만 쓸 수 있다. */
    data class NotSpeaking(
        val action: com.discordassistant.central.participation.domain.model.action.SocialActionKind,
    ) : ParticipationEmitOutcome

    /**
     * core 결정론 규칙이 즉시 SILENT 로 즉결(타인 지목·끝난 흐름·사적 핑퐁·봇/시스템·중복·빈 메시지) — 정책 미평가·emit
     * 미호출. [reasonCode] 는 어떤 규칙이 막았는지(관찰·테스트).
     */
    data class RuleSilent(
        val reasonCode: String,
    ) : ParticipationEmitOutcome

    /**
     * core 결정론 규칙이 WAIT 로 즉결(발화 묶음 미완·이어가는 연결어) — 이번 턴 발화 안 함. 정책 미평가·emit 미호출.
     */
    data class RuleWait(
        val reasonCode: String,
    ) : ParticipationEmitOutcome

    /** 후보 평가 뒤 actionruntime 예약이 중복 identity 등으로 거절됐다. 실행 quota 결과는 비동기 실행 감사에 남는다. */
    data class SchedulingRejected(
        val channelKey: String,
    ) : ParticipationEmitOutcome

    /**
     * attention 타이밍 게이트([ChannelAttentionGate])가 이번 턴을 보류시킴(min_gap 연타·typing 작성 중) — 디바운스
     * 등가로 과발화를 막는다. 정책/emit 미진입(발화 없음). [channelKey] 는 보류된 채널(관찰·테스트).
     */
    data class AttentionDeferred(
        val channelKey: String,
    ) : ParticipationEmitOutcome

    /** 더 최신 사람 메시지가 도착해 이 장면의 판단·생성·예약을 폐기했다. 원문 문맥은 이미 저장된 상태다. */
    data class Superseded(
        val stage: NiaTurnSupersessionStage,
    ) : ParticipationEmitOutcome

    /** SPEAK 분포 → emit 호출됨. 그 결과(예약/안전 하강 등). 실제 전송 여부는 ShadowMode 전송 경계가 별도 결정. */
    data class Emitted(
        val result: NexaSpeechEmitResult,
    ) : ParticipationEmitOutcome

    /** 평가/emit 중 예외 흡수 — 활성 participation 턴은 legacy 응답으로 우회하지 않는다. */
    data object Failed : ParticipationEmitOutcome
}

sealed interface ParticipationRawContextMutationOutcome {
    data object NoStore : ParticipationRawContextMutationOutcome

    data object Upserted : ParticipationRawContextMutationOutcome

    data class Redacted(
        val removedCount: Int,
    ) : ParticipationRawContextMutationOutcome {
        init {
            require(removedCount >= 0) { "removedCount 는 음수일 수 없다: $removedCount" }
        }
    }

    data object Failed : ParticipationRawContextMutationOutcome
}

private val ParticipationEmitOutcome.traceOutcome: String
    get() =
        when (this) {
            ParticipationEmitOutcome.Inactive -> "INACTIVE"
            is ParticipationEmitOutcome.NotSpeaking -> "NOT_SPEAKING"
            is ParticipationEmitOutcome.RuleSilent -> "RULE_SILENT"
            is ParticipationEmitOutcome.RuleWait -> "RULE_WAIT"
            is ParticipationEmitOutcome.SchedulingRejected -> "SCHEDULING_REJECTED"
            is ParticipationEmitOutcome.AttentionDeferred -> "ATTENTION_DEFERRED"
            is ParticipationEmitOutcome.Superseded -> "SUPERSEDED"
            is ParticipationEmitOutcome.Emitted -> "EMITTED"
            ParticipationEmitOutcome.Failed -> "FAILED"
        }

private val ParticipationEmitOutcome.traceReasonCode: String?
    get() =
        when (this) {
            is ParticipationEmitOutcome.RuleSilent -> reasonCode
            is ParticipationEmitOutcome.RuleWait -> reasonCode
            is ParticipationEmitOutcome.SchedulingRejected -> "SCHEDULING_REJECTED"
            is ParticipationEmitOutcome.AttentionDeferred -> "ATTENTION_DEFERRED"
            is ParticipationEmitOutcome.Superseded -> stage.reasonCode
            else -> null
        }

private val ParticipationEmitOutcome.tracePolicyAction: String?
    get() =
        when (this) {
            is ParticipationEmitOutcome.NotSpeaking -> action.wireName
            is ParticipationEmitOutcome.Emitted -> SocialActionKind.SPEAK.wireName
            else -> null
        }

private val ParticipationEmitOutcome.traceSafeAction: String?
    get() =
        (this as? ParticipationEmitOutcome.Emitted)
            ?.result
            ?.safeDecision
            ?.finalAction
            ?.wireName

private val ParticipationEmitOutcome.traceSpeechOutcome: String?
    get() =
        (this as? ParticipationEmitOutcome.Emitted)
            ?.result
            ?.pipelineResult
            ?.outcome
            ?.name

private val ParticipationEmitOutcome.traceConsentStage: String?
    get() =
        (this as? ParticipationEmitOutcome.Emitted)
            ?.result
            ?.pipelineResult
            ?.consentStage
            ?.name

private val ParticipationEmitOutcome.traceWillSpeak: Boolean?
    get() = (this as? ParticipationEmitOutcome.Emitted)?.result?.willSpeak

enum class NiaTurnSupersessionStage(
    val reasonCode: String,
) {
    BEFORE_JUDGE("SUPERSEDED_BEFORE_JUDGE"),
    AFTER_JUDGE("SUPERSEDED_AFTER_JUDGE"),
    BEFORE_SPEECH_GENERATION("SUPERSEDED_BEFORE_SPEECH_GENERATION"),
    BEFORE_SCHEDULE("SUPERSEDED_BEFORE_SCHEDULE"),
}
