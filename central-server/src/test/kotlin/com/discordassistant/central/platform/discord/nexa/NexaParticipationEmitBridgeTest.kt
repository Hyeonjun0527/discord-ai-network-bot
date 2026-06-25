package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.ParticipationActionRouter
import com.discordassistant.central.actionruntime.application.port.out.ActionSchedulerPort
import com.discordassistant.central.actionruntime.application.port.out.ClaimedAction
import com.discordassistant.central.actionruntime.domain.model.ActionFailureReason
import com.discordassistant.central.actionruntime.domain.model.ActionIdentity
import com.discordassistant.central.actionruntime.domain.model.ScheduledActionType
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.participation.adapter.outbound.policy.baseline.CooldownHeuristicPolicy
import com.discordassistant.central.participation.application.BanterSafetyDecisionService
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.application.model.ShadowModelRegistry
import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ParticipationDecisionLogPort
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import com.discordassistant.central.quota.application.InMemoryRateLimitStore
import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.ConversationTurn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA participation 자발 발화 wiring(단계 1) 브리지 단위 테스트.
 *
 * 핵심 acceptance:
 *  - **flag OFF(기본 legacy)면 평가/emit 미진입**(기존 동작 100% 보존 — autoRespond 영향 0).
 *  - flag ON(SHADOW_PREDICT) + 멘션이면 정책이 SPEAK 로 접혀 **emit seam 을 통과**한다(예약은 되지만 전송 경계가
 *    ShadowMode 로 별도 차단 — 이 브리지는 emit 호출만 책임진다).
 *  - 정책이 SPEAK 가 아니면(IGNORE) emit 미호출.
 */
class NexaParticipationEmitBridgeTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `flag OFF 면 평가·emit 을 하지 않는다(기존 동작 보존)`() {
        val scheduler = FakeScheduler()
        val bridge =
            NexaParticipationEmitBridge(
                flags = flagService(ShadowMode.OFF),
                policy = CooldownHeuristicPolicy(),
                emit = emitSeam(consent = ConsentDecision.OBSERVE_AND_SPEAK, scheduler = scheduler),
                rateLimitStore = InMemoryRateLimitStore(),
                perChannelPerMin = 6,
                globalPerMin = 30,
            )

        val outcome = bridge.onMessage(signal(mentioned = true))

        assertThat(outcome).isEqualTo(ParticipationEmitOutcome.Inactive)
        assertThat(scheduler.scheduled).isEmpty() // emit 미진입 — 예약 0
    }

    @Test
    fun `flag ON(SHADOW_PREDICT) + 멘션이면 SPEAK 가 emit seam 을 통과한다`() {
        val scheduler = FakeScheduler()
        val bridge =
            NexaParticipationEmitBridge(
                flags = flagService(ShadowMode.SHADOW_PREDICT),
                policy = CooldownHeuristicPolicy(),
                emit =
                    emitSeam(
                        candidates = listOf(SpeechCandidate("c1", listOf("오 그거 재밌겠다"))),
                        consent = ConsentDecision.OBSERVE_AND_SPEAK,
                        scheduler = scheduler,
                    ),
                rateLimitStore = InMemoryRateLimitStore(),
                perChannelPerMin = 6,
                globalPerMin = 30,
            )

        val outcome = bridge.onMessage(signal(mentioned = true))

        assertThat(outcome).isInstanceOf(ParticipationEmitOutcome.Emitted::class.java)
        val emitted = outcome as ParticipationEmitOutcome.Emitted
        // emit seam 이 SPEAK 를 예약했다(실제 전송 차단은 ShadowMode 전송 경계가 별도 책임).
        assertThat(emitted.result.willSpeak).isTrue()
        assertThat(scheduler.scheduled.any { it.type == ScheduledActionType.SPEAK }).isTrue()
    }

    @Test
    fun `flag ON 이라도 정책이 SPEAK 가 아니면 emit 을 호출하지 않는다`() {
        val scheduler = FakeScheduler()
        val bridge =
            NexaParticipationEmitBridge(
                flags = flagService(ShadowMode.SHADOW_PREDICT),
                // cooldown 임계(기본 2.0) 이상으로 최근 발화량을 채워 멘션 없는 메시지는 IGNORE 로 접힌다.
                policy = CooldownHeuristicPolicy(),
                emit = emitSeam(consent = ConsentDecision.OBSERVE_AND_SPEAK, scheduler = scheduler),
                rateLimitStore = InMemoryRateLimitStore(),
                perChannelPerMin = 6,
                globalPerMin = 30,
            )

        val outcome = bridge.onMessage(signal(mentioned = false, recentAgentBurstCount = 5))

        assertThat(outcome).isInstanceOf(ParticipationEmitOutcome.NotSpeaking::class.java)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `rate limit 한도 내면 SPEAK 가 emit 된다`() {
        val scheduler = FakeScheduler()
        val emit = countingEmitSeam(scheduler)
        val bridge =
            NexaParticipationEmitBridge(
                flags = flagService(ShadowMode.LIVE),
                policy = CooldownHeuristicPolicy(),
                emit = emit.service,
                rateLimitStore = InMemoryRateLimitStore(),
                perChannelPerMin = 6,
                globalPerMin = 30,
            )

        val outcome = bridge.onMessage(signal(mentioned = true))

        assertThat(outcome).isInstanceOf(ParticipationEmitOutcome.Emitted::class.java)
        assertThat(emit.calls).isEqualTo(1) // 한도 내 — emit 정확히 1회
        assertThat(scheduler.scheduled.any { it.type == ScheduledActionType.SPEAK }).isTrue()
    }

    @Test
    fun `채널 한도 초과면 emit 을 호출하지 않고 RateLimited 를 돌려준다(토큰 0)`() {
        val scheduler = FakeScheduler()
        val emit = countingEmitSeam(scheduler)
        val bridge =
            NexaParticipationEmitBridge(
                flags = flagService(ShadowMode.LIVE),
                policy = CooldownHeuristicPolicy(),
                emit = emit.service,
                rateLimitStore = InMemoryRateLimitStore(),
                // 채널 한도 1 — 두 번째 SPEAK 는 채널 게이트에 막힌다.
                perChannelPerMin = 1,
                globalPerMin = 30,
            )

        val first = bridge.onMessage(signal(mentioned = true))
        val second = bridge.onMessage(signal(mentioned = true))

        assertThat(first).isInstanceOf(ParticipationEmitOutcome.Emitted::class.java)
        assertThat(second).isInstanceOf(ParticipationEmitOutcome.RateLimited::class.java)
        assertThat(emit.calls).isEqualTo(1) // 한도 초과분은 emit 미호출 — GLM 토큰 0
    }

    @Test
    fun `전역 한도 초과면 다른 채널이라도 emit 을 호출하지 않는다(토큰 0)`() {
        val scheduler = FakeScheduler()
        val emit = countingEmitSeam(scheduler)
        val bridge =
            NexaParticipationEmitBridge(
                flags = flagService(ShadowMode.LIVE),
                policy = CooldownHeuristicPolicy(),
                emit = emit.service,
                rateLimitStore = InMemoryRateLimitStore(),
                // 채널 한도는 넉넉, 전역 한도 1 — 다른 채널의 두 번째 SPEAK 는 전역 게이트에 막힌다.
                perChannelPerMin = 10,
                globalPerMin = 1,
            )

        val first = bridge.onMessage(signal(mentioned = true, channelId = 100L))
        val second = bridge.onMessage(signal(mentioned = true, channelId = 200L))

        assertThat(first).isInstanceOf(ParticipationEmitOutcome.Emitted::class.java)
        assertThat(second).isInstanceOf(ParticipationEmitOutcome.RateLimited::class.java)
        assertThat(emit.calls).isEqualTo(1) // 전역 초과분은 emit 미호출 — GLM 토큰 0
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun signal(
        mentioned: Boolean,
        recentAgentBurstCount: Int = 0,
        channelId: Long = 3L,
    ): ParticipationMessageSignal =
        ParticipationMessageSignal(
            guildId = 1L,
            channelId = channelId,
            userId = 2L,
            mentioned = mentioned,
            recentAgentBurstCount = recentAgentBurstCount,
            recentTurns = listOf(ConversationTurn("user_2", "안녕")),
            sceneSeq = 10L,
            contextVersion = 1L,
            seed = 7L,
        )

    private fun flagService(mode: ShadowMode) = NexaParticipationFlagService(FakeModeStore(mode), FakeFlagPort(), "OFF")

    private fun emitSeam(
        candidates: List<SpeechCandidate> = listOf(SpeechCandidate("c1", listOf("좋아"))),
        consent: ConsentDecision,
        scheduler: FakeScheduler,
    ): NexaSpeechEmitService {
        val consentPolicy = ConsentPolicyPort { _, _, _ -> consent }
        val generationService =
            CandidateGenerationService(
                generationPort = FakeGenerationPort(candidates),
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
            )
        val pipeline =
            NexaSpeechPipelineService(
                consentGate = PolicyBackedConsentGate(consentPolicy),
                generationGate = SpeechGenerationGate(generationService),
                candidateSelector = NexaSpeechPipelineService.securityCriticSelector(),
                decisionLog = CapturingSpeechLog(),
            )
        return NexaSpeechEmitService(
            safetyDecision = BanterSafetyDecisionService(CapturingParticipationLog(), clock),
            pipeline = pipeline,
            actionRouter = ParticipationActionRouter(scheduler),
            modelRegistry = ShadowModelRegistry(InMemoryRegistryStore(), clock),
        )
    }

    /**
     * emit 호출 횟수를 세는 seam. [calls] = FakeGenerationPort.generate 호출 수 = emit 가 발화 파이프라인까지
     * 진입한 횟수(= GLM 토큰을 쓰는 지점). rate limit 으로 skip 되면 emit.emit 자체가 안 불려 0 으로 남는다.
     */
    private fun countingEmitSeam(scheduler: FakeScheduler): CountingEmit {
        val generationPort = CountingGenerationPort(listOf(SpeechCandidate("c1", listOf("좋아"))))
        val consentPolicy = ConsentPolicyPort { _, _, _ -> ConsentDecision.OBSERVE_AND_SPEAK }
        val generationService =
            CandidateGenerationService(
                generationPort = generationPort,
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
            )
        val pipeline =
            NexaSpeechPipelineService(
                consentGate = PolicyBackedConsentGate(consentPolicy),
                generationGate = SpeechGenerationGate(generationService),
                candidateSelector = NexaSpeechPipelineService.securityCriticSelector(),
                decisionLog = CapturingSpeechLog(),
            )
        val service =
            NexaSpeechEmitService(
                safetyDecision = BanterSafetyDecisionService(CapturingParticipationLog(), clock),
                pipeline = pipeline,
                actionRouter = ParticipationActionRouter(scheduler),
                modelRegistry = ShadowModelRegistry(InMemoryRegistryStore(), clock),
            )
        return CountingEmit(service, generationPort)
    }

    private class CountingEmit(
        val service: NexaSpeechEmitService,
        private val port: CountingGenerationPort,
    ) {
        val calls: Int get() = port.calls
    }

    private class CountingGenerationPort(
        private val candidates: List<SpeechCandidate>,
    ) : SpeechGenerationPort {
        var calls: Int = 0
            private set

        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            calls++
            return SpeechGenerationResult(candidates, modelMetadata = "mock")
        }
    }

    private class FakeGenerationPort(
        private val candidates: List<SpeechCandidate>,
    ) : SpeechGenerationPort {
        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult =
            SpeechGenerationResult(candidates, modelMetadata = "mock")
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
        ) = Unit

        override fun cancel(identity: ActionIdentity) = Unit

        override fun complete(identity: ActionIdentity) = Unit

        override fun fail(
            identity: ActionIdentity,
            reason: ActionFailureReason,
        ) = Unit

        override fun find(identity: ActionIdentity): ScheduledSocialAction? = null
    }

    private class InMemoryRegistryStore : com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort {
        private val store = mutableMapOf<String, com.discordassistant.central.participation.application.model.ShadowModelCandidate>()

        override fun find(modelId: String) = store[modelId]

        override fun save(candidate: com.discordassistant.central.participation.application.model.ShadowModelCandidate) {
            store[candidate.modelId] = candidate
        }

        override fun listAll() = store.values.toList()
    }

    private class FakeModeStore(
        private val mode: ShadowMode,
    ) : ShadowModeStorePort {
        override fun currentMode(guildPseudonym: String): ShadowMode = mode

        override fun applyTransition(audit: ShadowModeAudit) = Unit

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class FakeFlagPort : NexaParticipationFlagPort {
        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = null

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = emptySet()

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) = Unit

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) = Unit
    }
}
