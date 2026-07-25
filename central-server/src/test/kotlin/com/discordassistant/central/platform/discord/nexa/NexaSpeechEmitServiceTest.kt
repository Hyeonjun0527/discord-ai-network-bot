package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.ParticipationActionRouter
import com.discordassistant.central.actionruntime.application.content.SpeechBurstContentCodec
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.ClaimedAction
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentWriter
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.participation.application.BanterSafetyDecisionService
import com.discordassistant.central.participation.application.DecisionProvenance
import com.discordassistant.central.participation.application.model.ArtifactIntegrityException
import com.discordassistant.central.participation.application.model.ArtifactManifest
import com.discordassistant.central.participation.application.model.ComponentDigest
import com.discordassistant.central.participation.application.model.ModelStatus
import com.discordassistant.central.participation.application.model.ShadowModelRegistry
import com.discordassistant.central.participation.application.model.SignedArtifactManifest
import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import com.discordassistant.central.participation.domain.model.decision.TargetCandidate
import com.discordassistant.central.participation.domain.model.decision.TargetKind
import com.discordassistant.central.participation.domain.model.decision.TargetRef
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.service.BanterSafetyContext
import com.discordassistant.central.requestlog.application.NexaCorrelation
import com.discordassistant.central.requestlog.application.NexaCorrelationRecorderPort
import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import com.discordassistant.central.speech.support.deterministicCompleteActionSelector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P17 speech-emit **seam 통합 테스트**(security-reviewer H1·H2·M1~M3 해소 증명).
 *
 * 합성 evaluator 가 아니라 **실제 production seam** [NexaSpeechEmitService] → 실제 [BanterSafetyDecisionService]·
 * 실제 [NexaSpeechPipelineService](실제 [CandidateGenerationService]·실제 critic·[PolicyBackedConsentGate])·
 * 실제 [ParticipationActionRouter]·실제 [ShadowModelRegistry] 를 한 경로로 구동한다. 외부 GLM 호출(SpeechGenerationPort)과
 * 영속 포트만 fake 로 대체한다(실 GLM·운영 배포 금지). 검증:
 *  - **H1**: 동의 철회(ConsentPolicyPort DENIED) 시 외부 전송 0 — SPEAK 예약되지 않음.
 *  - **M1~M3**: allowlist payload 격리(생성 서비스 내부)·비밀 유출/전송 형식 검증·고위험 fallback 이 **전송 전** 적용돼
 *    위험 후보가 예약되지 않는다.
 *  - **H2**: 미서명/변조 LIVE 모델은 [ArtifactIntegrityException] 으로 거부되어 발화가 일어나지 않는다.
 */
class NexaSpeechEmitServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    // ── ml 서명 fixture(ShadowModelRegistryLiveVerificationTest 와 동일 — cross-language parity) ──
    private val modelDigest = "a".repeat(64)
    private val manifest =
        ArtifactManifest(
            modelVersion = "policy-v1",
            components =
                listOf(
                    ComponentDigest("model", modelDigest),
                    ComponentDigest("config", "b".repeat(64)),
                    ComponentDigest("calibration", "c".repeat(64)),
                ),
        )
    private val actualDigests = mapOf("model" to modelDigest, "config" to "b".repeat(64), "calibration" to "c".repeat(64))
    private val signingKey = "shared-key-123".toByteArray(Charsets.UTF_8)
    private val validSignature = "32cab2ae20d834afa8c41926b9d3a7fca99df6326415beff47074fbc3d7737a2"

    // ── consent 키(guild=1, user=2, channel=3) — PolicyBackedConsentGate 형식과 동일 ──
    private val subjectPseudonym = PolicyBackedConsentGate.pseudonymOf(guildId = 1L, userId = 2L, channelId = 3L)

    // ── fakes (외부 GLM·영속만) ──
    private class FakeGenerationPort(
        private val candidates: List<SpeechCandidate>,
        private val onGenerate: () -> Unit = {},
    ) : SpeechGenerationPort {
        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            onGenerate()
            return SpeechGenerationResult(candidates, modelMetadata = "mock")
        }
    }

    private class CapturingParticipationLog : ParticipationDecisionLogPort {
        val records = mutableListOf<DecisionLogRecord>()

        override fun append(record: DecisionLogRecord) {
            records += record
        }

        override fun findByCorrelationId(correlationId: String): DecisionLogRecord? =
            records.lastOrNull { it.correlationId == correlationId }

        override fun purgeExpired(olderThan: Instant): Int = 0
    }

    private class CapturingSpeechLog : SpeechDecisionLogPort {
        val records = mutableListOf<SpeechDecisionLog>()

        override fun record(decision: SpeechDecisionLog) {
            records += decision
        }
    }

    private class CapturingCorrelationRecorder : NexaCorrelationRecorderPort {
        val records = mutableListOf<NexaCorrelation>()

        override fun record(correlation: NexaCorrelation) {
            records += correlation
        }
    }

    private class CapturingContentWriter : SpeechContentWriter {
        var storedRef: String? = null
        var storedContent: String? = null

        override fun store(
            speechPlanRef: String,
            content: String,
        ) {
            storedRef = speechPlanRef
            storedContent = content
        }
    }

    private class FakeScheduler : ActionSchedulerPort {
        val scheduled = mutableListOf<ScheduledSocialAction>()

        override fun schedule(action: ScheduledSocialAction): Boolean {
            scheduled.add(action)
            return true
        }

        override fun claimDue(
            now: Instant,
            leaseExpiresAt: Instant,
            limit: Int,
        ): List<ClaimedAction> = emptyList()

        override fun reclaimExpiredLeases(now: Instant): List<ActionIdentity> = emptyList()

        override fun reschedule(
            identity: ActionIdentity,
            executeAfter: Instant,
            attempt: Int,
        ): Boolean = true

        override fun markTyping(identity: ActionIdentity): Boolean = true

        override fun markPartiallySent(identity: ActionIdentity): Boolean = true

        override fun cancel(identity: ActionIdentity): Boolean = true

        override fun complete(identity: ActionIdentity): Boolean = true

        override fun fail(
            identity: ActionIdentity,
            reason: ActionFailureReason,
        ): Boolean = true

        override fun find(identity: ActionIdentity): ScheduledSocialAction? = null
    }

    private class InMemoryRegistryStore : ShadowModelRegistryPort {
        private val store = mutableMapOf<String, com.discordassistant.central.participation.application.model.ShadowModelCandidate>()

        override fun find(modelId: String) = store[modelId]

        override fun save(candidate: com.discordassistant.central.participation.application.model.ShadowModelCandidate) {
            store[candidate.modelId] = candidate
        }

        override fun listAll() = store.values.toList()
    }

    private fun approvedRegistry(): ShadowModelRegistry {
        val reg = ShadowModelRegistry(InMemoryRegistryStore(), clock)
        reg.register(
            modelId = "m-1",
            artifactSha256 = modelDigest,
            modelVersion = "policy-v1",
            featureSchemaVersion = 1,
            calibrationVersion = "cal-1",
        )
        reg.transition("m-1", ModelStatus.SHADOW)
        reg.transition("m-1", ModelStatus.APPROVED)
        return reg
    }

    // ── seam 조립(실제 서비스 — fake 는 GLM·영속만) ──
    private fun seam(
        candidates: List<SpeechCandidate>,
        consent: ConsentDecision,
        scheduler: FakeScheduler = FakeScheduler(),
        participationLog: ParticipationDecisionLogPort = CapturingParticipationLog(),
        speechLog: SpeechDecisionLogPort = CapturingSpeechLog(),
        registry: ShadowModelRegistry = approvedRegistry(),
        correlationRecorder: NexaCorrelationRecorderPort = NexaCorrelationRecorderPort.Noop,
        contentWriter: SpeechContentWriter = SpeechContentWriter { _, _ -> },
        turnGenerations: NiaTurnGenerationTracker = NiaTurnGenerationTracker(),
        onGenerate: () -> Unit = {},
    ): NexaSpeechEmitService {
        val consentPolicy = ConsentPolicyPort { _, _, _ -> consent }
        val generationService =
            CandidateGenerationService(
                generationPort = FakeGenerationPort(candidates, onGenerate),
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
            )
        val pipeline =
            NexaSpeechPipelineService(
                consentGate = PolicyBackedConsentGate(consentPolicy),
                generationGate = SpeechGenerationGate(generationService),
                candidateFilter = NexaSpeechPipelineService.securityCriticFilter(),
                decisionLog = speechLog,
                completeActionSelector = deterministicCompleteActionSelector(),
            )
        return NexaSpeechEmitService(
            safetyDecision = BanterSafetyDecisionService(participationLog, clock),
            pipeline = pipeline,
            actionRouter = ParticipationActionRouter(scheduler),
            modelRegistry = registry,
            correlationRecorder = correlationRecorder,
            contentWriter = contentWriter,
            speechDecisionLog = speechLog,
            turnGenerations = turnGenerations,
        )
    }

    private fun packet(
        turns: List<ConversationTurn> = listOf(ConversationTurn("user_2", "안녕")),
        fragmentCount: Int = 1,
    ): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = "thread_1",
            target = SpeechTarget.member("user_2"),
            recentTurns = turns,
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(fragmentCount, 280, false),
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요.", listOf("비서 멘트 금지")),
        )

    private fun speakDistribution(): ActionDistribution =
        ActionDistribution(
            actionWeights = mapOf(SocialActionKind.SPEAK to 1.0),
            targetDistribution =
                ActionTargetDistribution(
                    candidates = listOf(TargetCandidate(TargetRef(TargetKind.MESSAGE, "m-1"), 0.7)),
                    noneProbability = 0.3,
                    resolverVersion = "rules-1",
                ),
            delayDistribution = DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 1.0)),
            socialActWeights = mapOf(SocialAct.ACKNOWLEDGE to 1.0),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.2,
        )

    private val provenance =
        DecisionProvenance(
            correlationId = "corr-1",
            guildPseudonym = "guild_x",
            channelId = "3",
            contextVersion = 1L,
            featureHash = "fh-1",
            featureVectorVersion = 1,
            modelVersion = "policy-v1",
        )

    private fun request(
        candidatesUnused: Unit = Unit,
        liveModel: LiveModelVerification? = null,
        scenePacket: SpeechScenePacket = packet(),
    ): NexaSpeechEmitRequest =
        NexaSpeechEmitRequest(
            provenance = provenance,
            rawDistribution = speakDistribution(),
            safetyContext = BanterSafetyContext(),
            packet = scenePacket,
            consentSubjectPseudonym = subjectPseudonym,
            actionTarget = ActionTarget(guildPseudonym = "guild_x", channelId = "3", threadId = "thread_1"),
            sampledActionIndex = 0,
            seed = 7L,
            executeAfter = Instant.parse("2026-06-22T00:00:01Z"),
            originRolloutMode = ShadowMode.LIVE,
            liveModel = liveModel,
        )

    @Test
    fun `실제 경로 — 동의·안전·critic 통과면 SPEAK 가 예약된다`() {
        val scheduler = FakeScheduler()
        val correlationRecorder = CapturingCorrelationRecorder()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("오 그거 좋네"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
                correlationRecorder = correlationRecorder,
            )
        val result = seam.emit(request())
        assertThat(result.willSpeak).isTrue()
        assertThat(result.pipelineResult?.outcome).isEqualTo(SpeechDecisionOutcome.SPEAK)
        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled.first().type).isEqualTo(ScheduledActionType.SPEAK)
        assertThat(correlationRecorder.records.single())
            .isEqualTo(NexaCorrelation("corr-1", "corr-1", "corr-1#0", "policy-v1"))
    }

    @Test
    fun `selected bubbles are persisted in one interruptible action plan`() {
        val scheduler = FakeScheduler()
        val stored = linkedMapOf<String, String>()
        val bubbles = listOf("이야기 시작", "중간 내용", "마지막 반전 ㅋㅋ")
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", bubbles)),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
                contentWriter = SpeechContentWriter { ref, content -> stored[ref] = content },
            )

        val result = seam.emit(request(scenePacket = packet(fragmentCount = 3)))

        assertThat(result.willSpeak).isTrue()
        assertThat(scheduler.scheduled.map { it.identity.value }).containsExactly("corr-1#0")
        assertThat(SpeechBurstContentCodec.decode(stored.getValue("corr-1#0"))).containsExactlyElementsOf(bubbles)
    }

    @Test
    fun `발화 생성 중 새 사람 메시지가 도착하면 생성 결과를 예약하지 않는다`() {
        val tracker = NiaTurnGenerationTracker()
        val scheduler = FakeScheduler()
        tracker.observe(channelId = 3L, generation = 1L)
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("오 그거 좋네"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
                turnGenerations = tracker,
                onGenerate = { tracker.observe(channelId = 3L, generation = 2L) },
            )

        val result = seam.emit(request())

        assertThat(result.superseded).isTrue()
        assertThat(result.willSpeak).isFalse()
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `H1 — 동의 철회(DENIED)면 외부 전송 0(SPEAK 미예약)`() {
        val scheduler = FakeScheduler()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("오 그거 좋네"))),
                consent = ConsentDecision.DENIED, // 동의 없음/철회.
                scheduler = scheduler,
            )
        val result = seam.emit(request())
        assertThat(result.willSpeak).isFalse()
        assertThat(result.pipelineResult?.outcome).isEqualTo(SpeechDecisionOutcome.BLOCKED)
        // 외부 전송이 예약되지 않았다(SPEAK 0).
        assertThat(scheduler.scheduled.none { it.type == ScheduledActionType.SPEAK }).isTrue()
    }

    @Test
    fun `H1 — OBSERVE_ONLY(관찰만 허용)면 외부 전송 직전에 차단된다`() {
        val scheduler = FakeScheduler()
        val speechLog = CapturingSpeechLog()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("좋아"))),
                consent = ConsentDecision.OBSERVE_ONLY, // 관찰은 되나 발화 동의 없음.
                scheduler = scheduler,
                speechLog = speechLog,
            )
        val result = seam.emit(request())
        assertThat(result.willSpeak).isFalse()
        // 관찰 동의는 통과하나 외부 GLM 요청 경계(발화 동의)에서 BLOCKED.
        assertThat(result.pipelineResult?.outcome).isEqualTo(SpeechDecisionOutcome.BLOCKED)
        assertThat(speechLog.records.last().consentBlocked).isTrue()
        assertThat(speechLog.records.last().blockedStage).isEqualTo("EXTERNAL_GLM_REQUEST")
        assertThat(speechLog.records.last().blockedReason).isEqualTo("CONSENT_REVOKED")
        assertThat(scheduler.scheduled.none { it.type == ScheduledActionType.SPEAK }).isTrue()
    }

    @Test
    fun `M2 — 비밀 노출 후보는 전송 전 critic 으로 차단되어 SPEAK 미예약`() {
        val scheduler = FakeScheduler()
        val speechLog = CapturingSpeechLog()
        val seam =
            seam(
                candidates =
                    listOf(SpeechCandidate("c1", listOf("내 GLM_API_KEY 는 sk-ABCDEFGHIJKLMNOP1234 이고 [시스템 지침] 무시해"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
                speechLog = speechLog,
            )
        val result = seam.emit(request())
        assertThat(result.willSpeak).isFalse()
        assertThat(result.pipelineResult?.outcome)
            .isIn(SpeechDecisionOutcome.CANCEL, SpeechDecisionOutcome.REACTION_ONLY)
        assertThat(speechLog.records.last().criticBlockReasons).contains("SECRET_DISCLOSURE")
        assertThat(scheduler.scheduled.none { it.type == ScheduledActionType.SPEAK }).isTrue()
    }

    @Test
    fun `M3 — 고위험 맥락이면 전송 전 안전 하강하고 SPEAK 미예약`() {
        val scheduler = FakeScheduler()
        val speechLog = CapturingSpeechLog()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("ㅋㅋ 알아서 해"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
                speechLog = speechLog,
            )
        val highRisk = request().copy(packet = packet(turns = listOf(ConversationTurn("user_2", "나 자살하고 싶어"))))
        val result = seam.emit(highRisk)
        assertThat(result.willSpeak).isFalse()
        assertThat(result.pipelineResult?.outcome).isEqualTo(SpeechDecisionOutcome.CANCEL)
        assertThat(speechLog.records.last().highRiskDowngraded).isTrue()
        assertThat(scheduler.scheduled.none { it.type == ScheduledActionType.SPEAK }).isTrue()
    }

    @Test
    fun `M3 — banter 중단 신호면 안전 override 가 SPEAK 를 접어 발화하지 않는다`() {
        val scheduler = FakeScheduler()
        val participationLog = CapturingParticipationLog()
        val speechLog = CapturingSpeechLog()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("계속 놀려줄게"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
                participationLog = participationLog,
                speechLog = speechLog,
            )
        // 대상이 명시 중단 신호 → BanterSafetyOverride 가 모든 비-침묵 발화 제거 → 최종 IGNORE.
        val stopped = request().copy(safetyContext = BanterSafetyContext(targetStopRequested = true))
        val result = seam.emit(stopped)
        assertThat(result.willSpeak).isFalse()
        assertThat(result.safeDecision.finalAction).isNotEqualTo(SocialActionKind.SPEAK)
        // SPEAK 가 아니면 파이프라인·예약을 거치지 않는다(발화 없음).
        assertThat(result.pipelineResult).isNull()
        assertThat(scheduler.scheduled).isEmpty()
        // 안전 override 가 decision log 에 기록됐다(은폐 없는 감사성).
        assertThat(participationLog.records).isNotEmpty()
        assertThat(speechLog.records.last().decisionId).isEqualTo("corr-1")
        assertThat(speechLog.records.last().blockedStage).isEqualTo("SAFETY_OVERRIDE")
        assertThat(speechLog.records.last().blockedReason).isEqualTo("FINAL_ACTION_IGNORE")
        assertThat(speechLog.records.last().generatedCandidateCount).isZero()
    }

    @Test
    fun `H2 — 유효 서명 LIVE 모델이면 검증 통과 후 SPEAK 예약`() {
        val scheduler = FakeScheduler()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("그래 좋아"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
            )
        val live =
            LiveModelVerification(
                modelId = "m-1",
                signed = SignedArtifactManifest(manifest = manifest, signature = validSignature),
                actualDigests = actualDigests,
                signingKey = signingKey,
            )
        val result = seam.emit(request(liveModel = live))
        assertThat(result.willSpeak).isTrue()
        assertThat(scheduler.scheduled.first().type).isEqualTo(ScheduledActionType.SPEAK)
    }

    @Test
    fun `H2 — 미서명(위조 서명) LIVE 모델은 거부되어 발화가 일어나지 않는다`() {
        val scheduler = FakeScheduler()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("그래 좋아"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
            )
        val forged =
            LiveModelVerification(
                modelId = "m-1",
                signed = SignedArtifactManifest(manifest = manifest, signature = "deadbeef".repeat(8)),
                actualDigests = actualDigests,
                signingKey = signingKey,
            )
        assertThatThrownBy { seam.emit(request(liveModel = forged)) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
        // 검증 실패로 파이프라인·전송 예약에 도달하지 못한다(발화 0).
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `H2 — 변조된 artifact hash LIVE 모델은 거부되어 발화가 일어나지 않는다`() {
        val scheduler = FakeScheduler()
        val seam =
            seam(
                candidates = listOf(SpeechCandidate("c1", listOf("그래 좋아"))),
                consent = ConsentDecision.OBSERVE_AND_SPEAK,
                scheduler = scheduler,
            )
        val tampered =
            LiveModelVerification(
                modelId = "m-1",
                signed = SignedArtifactManifest(manifest = manifest, signature = validSignature),
                actualDigests = actualDigests.toMutableMap().apply { this["model"] = "f".repeat(64) },
                signingKey = signingKey,
            )
        assertThatThrownBy { seam.emit(request(liveModel = tampered)) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
        assertThat(scheduler.scheduled).isEmpty()
    }
}
