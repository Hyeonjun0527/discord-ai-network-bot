package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.ParticipationActionRouter
import com.discordassistant.central.actionruntime.application.port.out.ActionConsentPort
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.participation.application.BanterSafetyDecisionService
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.requestlog.application.NexaCorrelationRecorderPort
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.CompleteActionSelector
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleRagPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechFactualGroundingPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechInputTracePort
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.ConversationContentIsolator
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * NEXA speech-emit **seam 빈 배선**(NEXA-P17 enforcement). 흩어져 dormant 였던 enforcement application 클래스
 * ([NexaSpeechPipelineService]·[BanterSafetyDecisionService]·[selectForLiveVerified] 보유 [com.discordassistant.
 * central.participation.application.model.ShadowModelRegistry])를 **실제 production bean** 으로 등록해, 단일 seam
 * ([NexaSpeechEmitService], `@Service`)이 이들을 생성자 주입으로 묶도록 한다. 새 코드만 추가 — 기존 빈 정의 무변경.
 *
 * 핵심 배선:
 *  - **동의 통합(H1)**: [ConsentGate] 를 in-memory 평행 구현 대신 [PolicyBackedConsentGate] 로 바인딩한다 —
 *    발화 동의 검사가 ingestion 과 **같은** [ConsentPolicyPort](길드 활성/옵트아웃/채널 스코프 합성) 를 live 로
 *    조회한다. 동의 철회 시 speech-emit seam 이 실제로 차단된다. 실제 동의 어댑터가 따로 [ConsentGate] 를 등록하면
 *    [ConditionalOnMissingBean] 으로 이 기본 바인딩이 비활성화된다.
 *  - **생성 게이트**: [SpeechGenerationGate] 는 [SpeechGenerationPort](= [com.discordassistant.central.speech.
 *    adapter.outbound.routing.RoutingCloudSpeechGenerationAdapter] 빈) 를 [CandidateGenerationService] 뒤로만
 *    호출한다 — 외부 GLM 경로 접근은 이 seam 안에서 포트로만 일어난다(직접 호출은 NexaArchitectureTest 가 차단).
 *  - **dormant 보존**: 이 빈들이 등록돼도 [NexaSpeechEmitService.emit] 를 부르는 production 호출자는 NEXA flag
 *    ON + 실제 SPEAK emit 시에만 동작한다(상위 평가 유스케이스가 게이팅). flag OFF 면 호출자가 없어 무영향.
 */
@Configuration
class NexaSpeechEmitConfig {
    /** 동의 게이트 — 실제 consent 경로([ConsentPolicyPort]) 뒤로 통합(H1·단일 consent 판정). */
    @Bean
    @ConditionalOnMissingBean(ConsentGate::class)
    fun nexaPolicyBackedConsentGate(consentPolicy: ConsentPolicyPort): ConsentGate = PolicyBackedConsentGate(consentPolicy)

    /** 예약 뒤 실행되는 SEND/REACT도 같은 consent 정책을 즉시 다시 읽는다. */
    @Bean
    @ConditionalOnMissingBean(ActionConsentPort::class)
    fun nexaActionConsentPort(consentPolicy: ConsentPolicyPort): ActionConsentPort =
        ActionConsentPort { target ->
            val guildId = target.routingGuildId?.toLongOrNull()
            val channelId = target.routingChannelId?.toLongOrNull()
            val userId = target.routingUserId?.toLongOrNull()
            if (guildId == null || channelId == null || userId == null) {
                false
            } else {
                consentPolicy.observationDecision(guildId, userId, channelId).speechAllowed
            }
        }

    /** 발화 생성 게이트(SPEAK·not stale 일 때만 [SpeechGenerationPort] 호출). */
    @Bean
    @ConditionalOnMissingBean(SpeechGenerationGate::class)
    fun nexaSpeechGenerationGate(
        generationPort: SpeechGenerationPort,
        factualGrounding: SpeechFactualGroundingPort,
        inputTrace: SpeechInputTracePort,
        humanSpeechStyleRag: HumanSpeechStyleRagPort,
        promptSource: NiaPromptSource,
    ): SpeechGenerationGate =
        SpeechGenerationGate(
            CandidateGenerationService(
                generationPort = generationPort,
                burstCompiler = BurstPromptCompiler(promptSource),
                reasoningModeSelector = ReasoningModeSelector(),
                contentIsolator = ConversationContentIsolator(promptSource),
                factualGrounding = factualGrounding,
                inputTrace = inputTrace,
                promptSource = promptSource,
                humanSpeechStyleRag = humanSpeechStyleRag,
            ),
        )

    /** 발화 파이프라인 seam(H1·M1·M2·M3 — 동의·critic·고위험 fallback·decision log). */
    @Bean
    @ConditionalOnMissingBean(NexaSpeechPipelineService::class)
    fun nexaSpeechPipelineService(
        consentGate: ConsentGate,
        generationGate: SpeechGenerationGate,
        decisionLog: SpeechDecisionLogPort,
    ): NexaSpeechPipelineService =
        NexaSpeechPipelineService(
            consentGate = consentGate,
            generationGate = generationGate,
            candidateFilter = NexaSpeechPipelineService.securityCriticFilter(),
            decisionLog = decisionLog,
            completeActionSelector = CompleteActionSelector(),
        )

    /** speech decision log 미바인딩 환경 기본값(Noop) — 실제 sink 어댑터가 있으면 그쪽이 우선. */
    @Bean
    @ConditionalOnMissingBean(SpeechDecisionLogPort::class)
    fun nexaNoopSpeechDecisionLog(): SpeechDecisionLogPort = SpeechDecisionLogPort.Noop

    /** requestlog correlation recorder 미바인딩 환경 기본값(Noop). */
    @Bean
    @ConditionalOnMissingBean(NexaCorrelationRecorderPort::class)
    fun nexaNoopCorrelationRecorder(): NexaCorrelationRecorderPort = NexaCorrelationRecorderPort.Noop

    /** banter 안전 override + decision log(M3) — 전송될 행동을 정하기 전 위험 act/SPEAK 제거. */
    @Bean
    @ConditionalOnMissingBean(BanterSafetyDecisionService::class)
    fun nexaBanterSafetyDecisionService(decisionLog: ParticipationDecisionLogPort): BanterSafetyDecisionService =
        BanterSafetyDecisionService(decisionLog)

    /** participation 결정 → actionruntime 전송 라우터(전송 경계). */
    @Bean
    @ConditionalOnMissingBean(ParticipationActionRouter::class)
    fun nexaParticipationActionRouter(scheduler: ActionSchedulerPort): ParticipationActionRouter = ParticipationActionRouter(scheduler)
}
