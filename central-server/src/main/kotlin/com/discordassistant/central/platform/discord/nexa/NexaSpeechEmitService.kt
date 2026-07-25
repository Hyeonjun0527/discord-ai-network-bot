package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.ParticipationActionRouter
import com.discordassistant.central.actionruntime.application.RouteResult
import com.discordassistant.central.actionruntime.application.content.SpeechBurstContentCodec
import com.discordassistant.central.actionruntime.application.port.out.ExecutionLimits
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentWriter
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.participation.application.BanterSafetyDecisionService
import com.discordassistant.central.participation.application.DecisionProvenance
import com.discordassistant.central.participation.application.SafeDecision
import com.discordassistant.central.participation.application.model.ShadowModelRegistry
import com.discordassistant.central.participation.application.model.SignedArtifactManifest
import com.discordassistant.central.participation.domain.model.action.ReactionCode
import com.discordassistant.central.participation.domain.model.action.SocialAction
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.action.SpeechRequestRef
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.service.BanterSafetyContext
import com.discordassistant.central.requestlog.application.NexaCorrelation
import com.discordassistant.central.requestlog.application.NexaCorrelationRecorderPort
import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.PipelineResult
import com.discordassistant.central.speech.application.generation.GenerationBudget
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.application.port.out.SpeechTraceContext
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * NEXA 발화 emit **단일 보안 seam**(NEXA-P17 enforcement, platform 어댑터).
 *
 * participation SPEAK 결정 → speech 생성 → actionruntime 전송 경로가 **반드시** 이 한 곳을 통과하도록 묶는,
 * 우회 불가능한 production seam 이다(security-reviewer H1·H2·M1~M3 해소). 흩어져 있던 enforcement bean 들을
 * 실제 production 호출자(이 seam)에 연결해, 발화가 일어나려면 다음 순서가 **강제**되게 한다:
 *
 *  1. **안전 override + decision log**([BanterSafetyDecisionService], M3): raw 분포를 banter 안전 override 로
 *     후처리한 뒤 seed 로 접어 최종 행동을 정하고 raw/override 를 decision log 에 남긴다. 위험 socialAct·SPEAK 가
 *     **전송될 행동을 정하기 전** 제거된다. 최종 행동이 SPEAK 가 아니면 여기서 끝(발화 없음).
 *  2. **LIVE 모델 무결성 검증**([ShadowModelRegistry.selectForLiveVerified], H2): SPEAK 가 외부 모델(LIVE)을
 *     쓰면, 승격은 반드시 서명·hash 검증을 거친다 — 미서명/변조 artifact 는 [com.discordassistant.central.
 *     participation.application.model.ArtifactIntegrityException] 으로 차단되어 **발화가 일어나지 않는다**. 검증
 *     없는 LIVE 승격 경로는 이 seam 에 존재하지 않는다(상태(APPROVED)만으로 LIVE 불가).
 *  3. **발화 파이프라인**([NexaSpeechPipelineService], H1·M1·M2): ConsentGate(생성 직전·외부 전송 직전 2회 동의
 *     재확인 — 철회/OBSERVE_ONLY 면 BLOCKED) → allowlist payload 격리(생성 서비스 내부) → 생성 → 비밀 유출·전송 형식
 *     검증 + 고위험 fallback(전송 전 차단) → decision log. 동의가 없으면 후보 생성·외부 전송이 0 이다.
 *  4. **전송 예약**([ParticipationActionRouter], 전송 경계): 파이프라인이 SPEAK 로 통과한 경우에만 SPEAK 를 예약한다
 *     (실제 send 의 shadow OBSERVE_ONLY hard block 은 actionruntime executor 가 별도 책임 — 본 seam 은 우회 차단·
 *     순서 강제). 파이프라인이 차단/침묵이면 IGNORE 로 라우팅해 전송을 예약하지 않는다.
 *
 * **dormant 보존**: 이 seam 은 NEXA flag 가 ON 이고 실제 SPEAK 가 emit 될 때만 호출된다(상위 평가 유스케이스가 게이팅).
 * flag OFF 면 호출자가 없어 NEXA 는 말하지 않는다 — 기존 channelai/기존 흐름 100% 무영향. 켜지면 위 seam 을 **반드시**
 * 통과한다(우회는 NexaArchitectureTest 의 [RoutingCloudSpeechGenerationAdapter] 직접 호출 금지 규칙이 구조적으로 차단).
 *
 * 순수성 경계: platform 어댑터 — participation/speech/actionruntime 의 **공개 application 클래스·도메인 값 객체**만
 * 조립한다(adapter 가 여러 도메인 application 을 묶는 것은 허용). 하위 adapter 구현·JDA 전송은 참조하지 않는다.
 */
@Service
class NexaSpeechEmitService(
    private val safetyDecision: BanterSafetyDecisionService,
    private val pipeline: NexaSpeechPipelineService,
    private val actionRouter: ParticipationActionRouter,
    private val modelRegistry: ShadowModelRegistry,
    private val correlationRecorder: NexaCorrelationRecorderPort,
    // 확정된 발화 본문을 참조(actionIdentity)로 저장하는 아웃바운드 포트(NEXA-P13-T003). SPEAK 예약 성공 시에만
    // 저장하며, 저장 실패는 흡수한다(발화 emit 경로를 깨뜨리지 않는다). flag 와 무관하게 항상 배선된다(원문 저장은
    // 무해하고 전송 단계에서 필요하다) — 실제 전송 활성화는 autonomous-send flag 가 별도로 게이팅한다.
    private val contentWriter: SpeechContentWriter,
    private val speechDecisionLog: SpeechDecisionLogPort = SpeechDecisionLogPort.Noop,
    private val clock: Clock = Clock.systemUTC(),
    private val turnGenerations: NiaTurnGenerationTracker = NiaTurnGenerationTracker(),
) {
    private val log = LoggerFactory.getLogger(NexaSpeechEmitService::class.java)

    /**
     * 한 participation 평가 결과([request])를 단일 seam 으로 emit 한다. 안전 override → (필요 시)LIVE 모델 검증 →
     * 발화 파이프라인 → 전송 예약 순서를 강제하며, 어느 게이트라도 막으면 발화 없이 안전 종료한다. 무엇이 일어났는지
     * ([NexaSpeechEmitResult])를 돌려준다(감사·테스트 — 실제 경로 enforcement 의 관찰 증거).
     */
    fun emit(request: NexaSpeechEmitRequest): NexaSpeechEmitResult {
        // 1) 안전 override + decision log(전송될 행동을 정하기 전 위험 act/SPEAK 제거). raw 분포가 SPEAK 로 접혀도
        //    override 가 위험하면 IGNORE 로 안전 하강한다.
        val safe: SafeDecision =
            safetyDecision.decideAndLog(
                provenance = request.provenance,
                raw = request.rawDistribution,
                safetyContext = request.safetyContext,
                seed = request.seed,
            )
        if (safe.finalAction != SocialActionKind.SPEAK) {
            // SPEAK 아님 — 발화·외부 전송 없음(예약하지 않음).
            recordSpeechNotInvoked(request, safe)
            return NexaSpeechEmitResult.notSpeaking(safe)
        }

        // 2) LIVE 모델 무결성 검증(H2). LIVE artifact 를 쓰는 경우 검증 없는 승격을 차단한다 — 검증 실패는
        //    ArtifactIntegrityException 으로 전파되어 발화가 일어나지 않는다(미서명/변조 LIVE 거부).
        request.liveModel?.let { live ->
            modelRegistry.selectForLiveVerified(
                modelId = live.modelId,
                signed = live.signed,
                actualDigests = live.actualDigests,
                signingKey = live.signingKey,
            )
        }

        // 3) 발화 파이프라인(동의 게이트·critic·고위험 fallback·decision log). 동의 철회/critic 차단/고위험이면
        //    BLOCKED/CANCEL/REACTION_ONLY 로 끝나고 외부 전송이 0 이다.
        val pipelineResult: PipelineResult =
            pipeline.run(
                subjectPseudonym = request.consentSubjectPseudonym,
                trigger = SpeechTrigger.SPEAK,
                packet = request.packet,
                stale = request.stale,
                budget = request.budget,
                traceContext =
                    SpeechTraceContext(
                        decisionId = request.provenance.correlationId,
                        correlationId = request.provenance.correlationId,
                    ),
            )

        val pipelineWillSpeak = pipelineResult.outcome == SpeechDecisionOutcome.SPEAK && pipelineResult.selected != null
        if (pipelineWillSpeak && !isLatestScene(request)) {
            log.info(
                "NIA_TURN_SUPERSEDED stage=BEFORE_SCHEDULE channel={} generation={}",
                request.provenance.channelId,
                request.provenance.contextVersion,
            )
            return NexaSpeechEmitResult.superseded(safe, pipelineResult)
        }

        // 4) 전송 예약 — 파이프라인이 실제 SPEAK 로 통과했을 때만 SPEAK 예약. 그 외에는 IGNORE 라우팅(예약 없음).
        val action: SocialAction =
            when {
                pipelineWillSpeak ->
                    SocialAction.Speak(SpeechRequestRef(correlationId = request.provenance.correlationId))

                pipelineResult.outcome == SpeechDecisionOutcome.REACTION_ONLY ->
                    SocialAction.React(reactionCodes = listOf(ReactionCode("ack")))

                else -> SocialAction.Ignore
            }
        val routeResult = routeCompleteAction(request, action)
        correlationRecorder.record(
            NexaCorrelation(
                correlationId = request.provenance.correlationId,
                decisionId = request.provenance.correlationId,
                actionId =
                    if (routeResult is RouteResult.Scheduled) {
                        ActionIdentity.of(request.provenance.correlationId, request.sampledActionIndex).value
                    } else {
                        null
                    },
                modelVersion = request.provenance.modelVersion,
            ),
        )

        // 5) SPEAK 가 새로 예약됐으면 확정된 후보 본문을 참조(actionIdentity)로 저장한다 — 전송 executor 가 이 참조를
        //    본문으로 풀어 전송한다(원문 보호 경계 T003). 저장은 emit 성공 여부와 무관하게 방어적으로만 한다: 실패해도
        //    emit 경로를 깨뜨리지 않는다(runCatching + 로그). 후보 텍스트가 알려진 유일한 지점이 여기(파이프라인 결과)다.
        persistSpeechContent(request, pipelineResult, routeResult)
        return NexaSpeechEmitResult(
            safeDecision = safe,
            pipelineResult = pipelineResult,
            routeResult = routeResult,
        )
    }

    private fun isLatestScene(request: NexaSpeechEmitRequest): Boolean {
        val channelId = (request.actionTarget.routingChannelId ?: request.actionTarget.channelId).toLongOrNull() ?: return true
        return turnGenerations.isLatest(channelId, request.turnGeneration)
    }

    /** 완전 행동 하나를 예약한다. 선택된 버블들은 본문 계획에 함께 저장되어 실행 중 매 버블 전에 최신 장면을 다시 본다. */
    private fun routeCompleteAction(
        request: NexaSpeechEmitRequest,
        action: SocialAction,
    ): RouteResult =
        actionRouter.route(
            decisionId = request.provenance.correlationId,
            sampledActionIndex = request.sampledActionIndex,
            action = action,
            target = request.actionTarget,
            executeAfter = request.executeAfter,
            contextVersion = request.turnGeneration,
            originRolloutMode = request.originRolloutMode,
            executionLimits = request.executionLimits,
            fulfillsPendingIntentId = request.fulfillsPendingIntentId,
        )

    private fun recordSpeechNotInvoked(
        request: NexaSpeechEmitRequest,
        safe: SafeDecision,
    ) {
        speechDecisionLog.record(
            SpeechDecisionLog(
                decisionId = request.provenance.correlationId,
                correlationId = request.provenance.correlationId,
                focusThreadKey = request.packet.focusThreadKey,
                socialAct = request.packet.socialAct,
                outcome =
                    if (safe.finalAction == SocialActionKind.REACT) {
                        SpeechDecisionOutcome.REACTION_ONLY
                    } else {
                        SpeechDecisionOutcome.CANCEL
                    },
                blockedStage = "SAFETY_OVERRIDE",
                blockedReason = "FINAL_ACTION_${safe.finalAction.name}",
                highRiskDowngraded = safe.safetyChanged,
                consentBlocked = false,
                generatedCandidateCount = 0,
                criticBlockReasons = emptySet(),
                selectedContentRef = null,
                createdAt = Instant.now(clock),
            ),
        )
    }

    /**
     * SPEAK 가 **새로** 예약된 경우([RouteResult.Scheduled] newlyScheduled=true) 선택된 후보의 버블 배열을 버전
     * codec으로 저장한다(참조 키 = [ActionIdentity].of(correlationId, sampledActionIndex).value — 예약 행동 identity 와 동일).
     * 저장 실패는 흡수한다(발화 emit·예약 경로 보호). 이미 예약된 결정 재처리(newlyScheduled=false)나 비 SPEAK 는 건너뛴다.
     */
    private fun persistSpeechContent(
        request: NexaSpeechEmitRequest,
        pipelineResult: PipelineResult,
        routeResult: RouteResult,
    ) {
        val selected = pipelineResult.selected ?: return
        if (routeResult !is RouteResult.Scheduled || !routeResult.newlyScheduled) return
        val bubbles = selected.bubbles.map(String::trim).filter(String::isNotEmpty)
        if (bubbles.isEmpty()) return
        val speechPlanRef = ActionIdentity.of(request.provenance.correlationId, request.sampledActionIndex).value
        runCatching { contentWriter.store(speechPlanRef, SpeechBurstContentCodec.encode(bubbles)) }
            .onFailure { log.warn("발화 본문 저장 실패(ref={}) — 전송 시 미해결로 우아하게 종결됨: {}", speechPlanRef, it.message) }
    }
}

/**
 * speech-emit seam 입력(상위 평가 유스케이스가 조립). 안전 결정·발화 파이프라인·전송 예약에 필요한 **이미 만들어진**
 * 도메인/application 값 객체만 운반한다 — seam 은 enforcement 순서만 강제하고 입력 조립은 호출자가 한다(SRP).
 */
data class NexaSpeechEmitRequest(
    /** 결정 provenance(원문 비포함) — decision log·라우팅 식별. */
    val provenance: DecisionProvenance,
    /** 정책이 낸 raw 행동 분포(안전 override 전). */
    val rawDistribution: ActionDistribution,
    /** banter 안전 신호(opt-out·중단·반복 표적). */
    val safetyContext: BanterSafetyContext,
    /** 발화 장면 패킷(생성·critic·고위험 평가 입력). */
    val packet: SpeechScenePacket,
    /**
     * 동의 판정용 비가역 token([PolicyBackedConsentGate.pseudonymOf]). 파이프라인의 동의
     * 게이트가 이 키로 **live** consent 를 조회한다(철회/OBSERVE_ONLY 면 외부 전송 0).
     */
    val consentSubjectPseudonym: String,
    /** 전송 예약 대상(길드/채널/thread 식별 — 원문 비포함). */
    val actionTarget: ActionTarget,
    /** 예약된 sampled action index(멱등 키 일부). */
    val sampledActionIndex: Int,
    /** 결정론 seed(안전 override·후보 선택 재현 키). */
    val seed: Long,
    /** 예약 실행 시점(actionruntime executor 가 이후 실제 전송). */
    val executeAfter: Instant,
    /** 이 요청을 만든 participation 판단의 rollout 모드 — 이후 채널 승격이 전송 권한을 넓히지 못하게 한다. */
    val originRolloutMode: ShadowMode,
    /** Discord 수신 즉시 관찰한 채널 세대. 생성 중 새 메시지가 오면 projection 저장과 독립적으로 결과를 폐기한다. */
    val turnGeneration: Long = provenance.contextVersion,
    /** 장면 stale 여부(늦은 발화 금지 — 파이프라인이 비호출로 하강). */
    val stale: Boolean = false,
    /** 생성 예산(후보 수·token cap). */
    val budget: GenerationBudget = GenerationBudget.DEFAULT,
    /** 후보 평가 뒤 최종 SEND·REACT에만 소비하는 실행 상한. */
    val executionLimits: ExecutionLimits = ExecutionLimits(perChannel = 6, global = 30),
    /** 실제 수행 후 닫을 열린 약속. 전체 SEND 버스트가 Discord에서 성공한 뒤에만 완료된다. */
    val fulfillsPendingIntentId: String? = null,
    /**
     * LIVE 외부 모델 승격 검증 입력(H2). null 이면 LIVE 모델 승격이 관여하지 않는 발화(검증 생략). non-null 이면
     * 발화 전 [ShadowModelRegistry.selectForLiveVerified] 로 서명·hash 무결성을 강제한다(미서명/변조 거부).
     */
    val liveModel: LiveModelVerification? = null,
) {
    init {
        require(fulfillsPendingIntentId == null || fulfillsPendingIntentId.isNotBlank()) { "완료 약속 ID는 빈 문자열일 수 없다" }
    }
}

/** LIVE 모델 승격 무결성 검증 입력(H2) — seam 이 [ShadowModelRegistry.selectForLiveVerified] 에 그대로 전달한다. */
data class LiveModelVerification(
    val modelId: String,
    val signed: SignedArtifactManifest,
    val actualDigests: Map<String, String>,
    val signingKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveModelVerification) return false
        return modelId == other.modelId &&
            signed == other.signed &&
            actualDigests == other.actualDigests &&
            signingKey.contentEquals(other.signingKey)
    }

    override fun hashCode(): Int {
        var result = modelId.hashCode()
        result = 31 * result + signed.hashCode()
        result = 31 * result + actualDigests.hashCode()
        result = 31 * result + signingKey.contentHashCode()
        return result
    }
}

/**
 * speech-emit seam 결과(감사·테스트). 안전 결정·파이프라인 결과·라우팅 결과를 함께 노출해 enforcement 가 실제 경로에서
 * 작동했음을 관찰 가능하게 한다. [willSpeak] 가 true 일 때만 외부 전송이 예약된다.
 */
data class NexaSpeechEmitResult(
    val safeDecision: SafeDecision,
    /** 발화 파이프라인 결과(SPEAK 아니면 null — 파이프라인 미진입). */
    val pipelineResult: PipelineResult?,
    /** 전송 라우팅 결과(SPEAK 아니면 null — 예약 미시도). */
    val routeResult: RouteResult?,
    /** 발화 생성 중 새 사람 메시지가 도착해 예약하지 않은 결과. */
    val superseded: Boolean = false,
) {
    /** 실제로 발화가 예약됐는가(파이프라인 SPEAK 통과 + SPEAK 예약). */
    val willSpeak: Boolean
        get() = pipelineResult?.willSpeak == true && routeResult is RouteResult.Scheduled

    val willReact: Boolean
        get() = pipelineResult?.outcome == SpeechDecisionOutcome.REACTION_ONLY && routeResult is RouteResult.Scheduled

    companion object {
        /** 안전 override 후 최종 행동이 SPEAK 가 아니어서 파이프라인·예약을 거치지 않은 결과(발화 없음). */
        fun notSpeaking(safe: SafeDecision): NexaSpeechEmitResult =
            NexaSpeechEmitResult(safeDecision = safe, pipelineResult = null, routeResult = null)

        fun superseded(
            safe: SafeDecision,
            pipelineResult: PipelineResult,
        ): NexaSpeechEmitResult =
            NexaSpeechEmitResult(
                safeDecision = safe,
                pipelineResult = pipelineResult,
                routeResult = null,
                superseded = true,
            )
    }
}
