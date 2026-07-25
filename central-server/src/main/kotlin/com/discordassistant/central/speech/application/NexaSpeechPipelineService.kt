package com.discordassistant.central.speech.application

import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.global.privacy.ConsentRevokedException
import com.discordassistant.central.global.privacy.ProcessingStage
import com.discordassistant.central.speech.application.generation.CandidateCriticFilter
import com.discordassistant.central.speech.application.generation.CompleteActionSelection
import com.discordassistant.central.speech.application.generation.CompleteActionSelector
import com.discordassistant.central.speech.application.generation.FallbackSpeechPolicy
import com.discordassistant.central.speech.application.generation.GenerationBudget
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.generation.SpeechOutcome
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.application.port.out.SpeechTraceContext
import com.discordassistant.central.speech.application.safety.HighRiskDirective
import com.discordassistant.central.speech.application.safety.HighRiskFallbackBoundary
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.service.critic.BurstShapeCritic
import com.discordassistant.central.speech.domain.service.critic.SecretDisclosureCritic
import java.time.Clock
import java.time.Instant

/**
 * NEXA 발화 파이프라인 오케스트레이터(NEXA-P17 보안 enforcement seam, application).
 *
 * participation 이 SPEAK 를 고른 뒤 speech 가 실제 후보를 생성·검증·선택해 **전송 직전** 결과를 내는 단일 실제
 * 유스케이스 경로다. 흩어져 있던 보안 enforcement 클래스를 이 한 경로에 **모두 연결**해, 합성 테스트가 아니라
 * 실제 서비스 호출에서 동의 철회·고위험 fallback·critic 차단·결정 로그가 작동하게 한다(security-reviewer H1/M2/M3 해소):
 *
 *  1. **동의 게이트(H1, T010)**: 생성 직전([ProcessingStage.SPEECH_GENERATION])과 외부 전송 직전
 *     ([ProcessingStage.EXTERNAL_GLM_REQUEST])에 [ConsentGate.checkAllowed] 를 호출한다. 철회/미동의면
 *     [SpeechDecisionOutcome.BLOCKED] 로 끝나고 **어떤 후보 생성/외부 전송도 일어나지 않는다**.
 *  2. **후보 생성(T002/T004 payload 격리는 CandidateGenerationService 안에서 적용됨)**: [SpeechGenerationGate]
 *     가 SPEAK·not stale 일 때만 generation 포트를 호출한다.
 *  3. **고위험 fallback(M3, T016)**: [HighRiskFallbackBoundary] 가 자해/위기/의료/법률 맥락이면 안전 directive 로
 *     하강한다 — 고위험/분류실패면 발화를 취소(BLOCKED→CANCEL)하고 조롱·확신을 차단한다.
 *  4. **전송 제약(M2, T003)**: [CandidateCriticFilter]가 비밀 노출과 버블 형식을 검증하고,
 *     [CompleteActionSelector]가 생존 후보 중 불확실성이 가장 낮은 하나를 고른다.
 *  5. **fallback 정책(T016)**: critic 통과 후보가 0 이면 [FallbackSpeechPolicy] 가 침묵/리액션으로 안전 하강한다.
 *  6. **결정 로그(M3, T015/T016)**: 위 결정을 [SpeechDecisionLogPort] 로 원문 없이 기록한다(decision-log sink 소비).
 *
 * feature flag OFF(이 서비스 미호출)면 기존 channelai/기존 흐름은 100% 그대로다 — NEXA 경로에만 enforcement 가 붙는다.
 *
 * 순수성: application — speech application/domain 값 객체 + global.privacy 동의 게이트 + 표준 타입만. Spring/JPA/JDA·
 * glm/zai·participation 타입 미참조(NexaArchitectureTest speech 규칙 준수).
 */
class NexaSpeechPipelineService(
    private val consentGate: ConsentGate,
    private val generationGate: SpeechGenerationGate,
    private val candidateFilter: CandidateCriticFilter,
    private val highRiskBoundary: HighRiskFallbackBoundary = HighRiskFallbackBoundary(),
    private val fallbackPolicy: FallbackSpeechPolicy = FallbackSpeechPolicy(),
    private val decisionLog: SpeechDecisionLogPort = SpeechDecisionLogPort.Noop,
    private val completeActionSelector: CompleteActionSelector = CompleteActionSelector(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 한 SPEAK 결정에 대해 실제 발화 파이프라인을 구동한다. [subjectPseudonym] 의 동의가 살아 있을 때만 생성·전송하고,
     * 고위험 맥락·critic 차단·동의 철회에서 안전하게 하강한다. 반환은 전송 직전 최종 결과다(전송 자체는 actionruntime 이 함).
     */
    fun run(
        subjectPseudonym: String,
        trigger: SpeechTrigger,
        packet: SpeechScenePacket,
        stale: Boolean = false,
        budget: GenerationBudget = GenerationBudget.DEFAULT,
        traceContext: SpeechTraceContext = SpeechTraceContext.UNLINKED,
    ): PipelineResult {
        // 1) 동의 게이트(생성 직전). 철회/미동의면 생성 포트를 한 번도 호출하지 않고 차단.
        try {
            consentGate.checkAllowed(subjectPseudonym, ProcessingStage.SPEECH_GENERATION)
        } catch (e: ConsentRevokedException) {
            return blocked(packet, traceContext, consentStage = e.stage)
        }

        // 2) 고위험 fallback 평가 — 고위험/분류실패면 발화를 취소(조롱·확신 차단).
        val directive = highRiskBoundary.evaluate(packet)
        if (directive.suppressConfidence && !directive.isNormal) {
            // 고위험 맥락: 안전쪽으로 발화 취소(canned 장문 금지 — 침묵). 결정 로그에 하강 기록.
            return downgraded(packet, traceContext, directive)
        }

        // 3) SPEAK 이 아니거나 stale 이면 generation 포트를 호출하지 않는다. 이 경우 외부 요청 자체가 없으므로
        //    EXTERNAL_GLM_REQUEST 동의까지 요구하지 않는다.
        if (trigger != SpeechTrigger.SPEAK || stale) {
            val gate = generationGate.generateIfSpeaking(trigger = trigger, packet = packet, stale = stale, budget = budget)
            return cancel(
                packet,
                traceContext,
                directive,
                generated = 0,
                criticReasons = emptySet(),
                blockedStage = "GENERATION_GATE",
                blockedReason = "GENERATION_${gate.skipReason?.name ?: "SKIPPED"}",
            )
        }

        // 4) 외부 GLM 요청 직전 동의 재확인. SpeechGenerationPort 는 외부 routing/cloud LLM 어댑터로 이어지므로,
        //    발화 동의가 없으면 generation 포트를 호출하기 전에 막아 외부 요청 자체를 0 으로 만든다.
        try {
            consentGate.checkAllowed(subjectPseudonym, ProcessingStage.EXTERNAL_GLM_REQUEST)
        } catch (e: ConsentRevokedException) {
            return blocked(packet, traceContext, consentStage = e.stage)
        }

        // 5) SPEAK·not stale·동의 유효일 때만 generation 포트 호출(SpeechGenerationGate 가 강제).
        val gate = generationGate.generateIfSpeaking(trigger = trigger, packet = packet, stale = stale, budget = budget)
        if (!gate.invokedGeneration) {
            // 방어적 fallback. 위 preflight 에서 이미 걸러져야 한다.
            return cancel(
                packet,
                traceContext,
                directive,
                generated = 0,
                criticReasons = emptySet(),
                blockedStage = "GENERATION_GATE",
                blockedReason = "GENERATION_${gate.skipReason?.name ?: "SKIPPED"}",
            )
        }

        // 비밀 유출과 버블 형식을 검증한 후보 중 불확실성이 가장 낮은 하나를 고른다.
        val generated = gate.result.candidates
        val criticReasons =
            generated
                .flatMap { candidateFilter.rejectionReasons(it, packet) }
                .map { it.name }
                .toSet()
        val survivors = candidateFilter.survivors(generated, packet)
        val fallbackOutcome = fallbackPolicy.decide(gate.result, packet)
        val completeSelection =
            if (survivors.isNotEmpty() || fallbackOutcome == SpeechOutcome.ReactionOnly) {
                completeActionSelector.select(
                    speechCandidates = survivors,
                    packet = packet,
                    offerReaction = true,
                )
            } else {
                // 실질적 발화 후보도 없고 fallback도 취소라면 외부 평가 없이 침묵한다.
                CompleteActionSelection.Ignore
            }

        // 6) Judge가 확정한 SPEAK를 다시 모델 판단하지 않고 로컬 검사 생존 후보로 실행한다.
        return when (completeSelection) {
            is CompleteActionSelection.Send ->
                speak(packet, traceContext, directive, completeSelection.candidate, generated.size, criticReasons)
            is CompleteActionSelection.React ->
                reactionOnly(
                    packet,
                    traceContext,
                    directive,
                    generated.size,
                    criticReasons,
                    blockedStage = "COMPLETE_ACTION_SELECTION",
                    blockedReason = "VALUE_SELECTED_REACTION",
                )
            CompleteActionSelection.Ignore ->
                cancel(
                    packet,
                    traceContext,
                    directive,
                    generated.size,
                    criticReasons,
                    blockedStage = "COMPLETE_ACTION_SELECTION",
                    blockedReason = "VALUE_SELECTED_IGNORE",
                )
        }
    }

    private fun blocked(
        packet: SpeechScenePacket,
        traceContext: SpeechTraceContext,
        consentStage: ProcessingStage,
        generated: Int = 0,
    ): PipelineResult {
        val log =
            decisionFor(
                packet,
                traceContext,
                SpeechDecisionOutcome.BLOCKED,
                highRisk = false,
                consentBlocked = true,
                generated = generated,
                criticReasons = emptySet(),
                blockedStage = consentStage.name,
                blockedReason = "CONSENT_REVOKED",
            )
        decisionLog.record(log)
        return PipelineResult(SpeechDecisionOutcome.BLOCKED, selected = null, consentStage = consentStage)
    }

    private fun downgraded(
        packet: SpeechScenePacket,
        traceContext: SpeechTraceContext,
        directive: HighRiskDirective,
    ): PipelineResult {
        val log =
            decisionFor(
                packet,
                traceContext,
                SpeechDecisionOutcome.CANCEL,
                highRisk = true,
                consentBlocked = false,
                generated = 0,
                criticReasons = emptySet(),
                blockedStage = "HIGH_RISK_BOUNDARY",
                blockedReason = "HIGH_RISK_${directive.level.name}",
            )
        decisionLog.record(log)
        return PipelineResult(SpeechDecisionOutcome.CANCEL, selected = null, highRiskDirective = directive)
    }

    private fun speak(
        packet: SpeechScenePacket,
        traceContext: SpeechTraceContext,
        directive: HighRiskDirective,
        candidate: SpeechCandidate,
        generated: Int,
        criticReasons: Set<String>,
    ): PipelineResult {
        val log =
            decisionFor(
                packet,
                traceContext,
                SpeechDecisionOutcome.SPEAK,
                !directive.isNormal,
                consentBlocked = false,
                generated,
                criticReasons,
                selectedContentRef = candidate.candidateId,
            )
        decisionLog.record(log)
        return PipelineResult(SpeechDecisionOutcome.SPEAK, selected = candidate, highRiskDirective = directive)
    }

    private fun reactionOnly(
        packet: SpeechScenePacket,
        traceContext: SpeechTraceContext,
        directive: HighRiskDirective,
        generated: Int,
        criticReasons: Set<String>,
        blockedStage: String? = null,
        blockedReason: String? = null,
    ): PipelineResult {
        val log =
            decisionFor(
                packet,
                traceContext,
                SpeechDecisionOutcome.REACTION_ONLY,
                !directive.isNormal,
                consentBlocked = false,
                generated,
                criticReasons,
                blockedStage = blockedStage,
                blockedReason = blockedReason,
            )
        decisionLog.record(log)
        return PipelineResult(SpeechDecisionOutcome.REACTION_ONLY, selected = null, highRiskDirective = directive)
    }

    private fun cancel(
        packet: SpeechScenePacket,
        traceContext: SpeechTraceContext,
        directive: HighRiskDirective,
        generated: Int,
        criticReasons: Set<String>,
        blockedStage: String? = null,
        blockedReason: String? = null,
    ): PipelineResult {
        val log =
            decisionFor(
                packet,
                traceContext,
                SpeechDecisionOutcome.CANCEL,
                !directive.isNormal,
                consentBlocked = false,
                generated,
                criticReasons,
                blockedStage = blockedStage,
                blockedReason = blockedReason,
            )
        decisionLog.record(log)
        return PipelineResult(SpeechDecisionOutcome.CANCEL, selected = null, highRiskDirective = directive)
    }

    private fun decisionFor(
        packet: SpeechScenePacket,
        traceContext: SpeechTraceContext,
        outcome: SpeechDecisionOutcome,
        highRisk: Boolean,
        consentBlocked: Boolean,
        generated: Int,
        criticReasons: Set<String>,
        blockedStage: String? = null,
        blockedReason: String? = null,
        selectedContentRef: String? = null,
    ): SpeechDecisionLog =
        SpeechDecisionLog(
            decisionId = traceContext.decisionId,
            correlationId = traceContext.correlationId,
            focusThreadKey = packet.focusThreadKey,
            socialAct = packet.socialAct,
            outcome = outcome,
            blockedStage = blockedStage,
            blockedReason = blockedReason,
            highRiskDowngraded = highRisk,
            consentBlocked = consentBlocked,
            generatedCandidateCount = generated,
            criticBlockReasons = criticReasons,
            selectedContentRef = selectedContentRef,
            createdAt = Instant.now(clock),
        )

    companion object {
        /** 비밀 유출·전송 형식·요청 행위 수행 여부를 검사한다. 말투 품질은 발화 생성 모델이 담당한다. */
        fun securityCriticFilter(): CandidateCriticFilter =
            CandidateCriticFilter(
                critics =
                    listOf(
                        SecretDisclosureCritic(),
                        BurstShapeCritic(),
                        com.discordassistant.central.speech.domain.service.critic
                            .IntentFulfillmentCritic(),
                    ),
            )
    }
}

/**
 * 발화 파이프라인 최종 결과(전송 직전). [selected] 가 null 이면 발화하지 않는다(침묵/리액션/차단). 안전 enforcement 의
 * 관찰 가능한 증거 — consentStage 가 채워지면 동의 차단으로 외부 전송이 0 임을 뜻한다.
 */
data class PipelineResult(
    val outcome: SpeechDecisionOutcome,
    /** 선택된 발화 후보(SPEAK 일 때만 non-null). */
    val selected: SpeechCandidate?,
    /** 동의 차단이 일어난 단계(BLOCKED 일 때만 non-null). */
    val consentStage: ProcessingStage? = null,
    /** 적용된 고위험 directive(평가됐을 때). */
    val highRiskDirective: HighRiskDirective? = null,
) {
    /** 실제로 발화하는가(외부 전송 발생). */
    val willSpeak: Boolean
        get() = outcome == SpeechDecisionOutcome.SPEAK && selected != null
}
