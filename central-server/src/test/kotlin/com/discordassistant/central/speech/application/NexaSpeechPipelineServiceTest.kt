package com.discordassistant.central.speech.application

import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.global.privacy.ProcessingStage
import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.generation.SpeechTrigger
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionLogPort
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.port.out.SpeechTraceContext
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P17 보안 enforcement 통합 테스트(security-reviewer H1/M2/M3 해소 증명).
 *
 * 합성 in-test pipeline 이 아니라 **실제 서비스 클래스**([NexaSpeechPipelineService] → 실제
 * [CandidateGenerationService]·실제 critic·실제 [ConsentGate])를 구동한다. 외부 GLM 호출은 mock 포트로 대체한다
 * (실 GLM·운영 배포 금지). 다음을 실제 경로에서 검증한다:
 *  - H1: 동의 철회 시 생성/외부 전송이 0(BLOCKED).
 *  - M2: 비밀 노출 후보만 전송 전 critic 으로 차단(침묵 하강).
 *  - M3: 고위험 맥락에서 안전 하강 + decision log sink 가 결정을 소비.
 */
class NexaSpeechPipelineServiceTest {
    /** 후보를 그대로 돌려주는 mock generation 포트(외부 GLM 미호출 — provider-neutral). */
    private class FakePort(
        private val candidates: List<SpeechCandidate>,
    ) : SpeechGenerationPort {
        var calls = 0
            private set

        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            calls++
            return SpeechGenerationResult(candidates, modelMetadata = "mock")
        }
    }

    /** decision log sink — 기록된 결정을 수집(M3 소비 증명). */
    private class CapturingLog : SpeechDecisionLogPort {
        val records = mutableListOf<SpeechDecisionLog>()

        override fun record(decision: SpeechDecisionLog) {
            records += decision
        }
    }

    private fun packet(
        socialAct: SpeechSocialAct = SpeechSocialAct.ACKNOWLEDGE,
        turns: List<ConversationTurn> = listOf(ConversationTurn("user_1", "안녕")),
        burstShape: SpeechBurstShape = SpeechBurstShape(1, 280, false),
        speechIntent: String? = null,
        rawContextSceneData: String? = null,
    ): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = "thread_1",
            target = SpeechTarget.member("user_1"),
            recentTurns = turns,
            socialAct = socialAct,
            burstShape = burstShape,
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요.", listOf("비서 멘트 금지")),
            speechIntent = speechIntent,
            rawContextSceneData = rawContextSceneData,
        )

    private fun pipeline(
        candidates: List<SpeechCandidate>,
        gate: ConsentGate,
        log: SpeechDecisionLogPort = SpeechDecisionLogPort.Noop,
    ): NexaSpeechPipelineService = pipeline(FakePort(candidates), gate, log)

    private fun pipeline(
        generationPort: FakePort,
        gate: ConsentGate,
        log: SpeechDecisionLogPort = SpeechDecisionLogPort.Noop,
    ): NexaSpeechPipelineService {
        val generationService =
            CandidateGenerationService(
                generationPort = generationPort,
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
            )
        return NexaSpeechPipelineService(
            consentGate = gate,
            generationGate = SpeechGenerationGate(generationService),
            candidateSelector = NexaSpeechPipelineService.securityCriticSelector(),
            decisionLog = log,
        )
    }

    @Test
    fun `H1 — 동의 살아 있으면 실제 경로로 발화한다`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        val result =
            pipeline(listOf(SpeechCandidate("c1", listOf("오 그거 좋네"))), gate, log)
                .run(
                    "user_1",
                    SpeechTrigger.SPEAK,
                    packet(),
                    seed = 1L,
                    traceContext = SpeechTraceContext(decisionId = "participation:thread_1:1"),
                )
        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.SPEAK)
        assertThat(result.willSpeak).isTrue()
        val last = log.records.last()
        assertThat(last.outcome).isEqualTo(SpeechDecisionOutcome.SPEAK)
        assertThat(last.decisionId).isEqualTo("participation:thread_1:1")
        assertThat(last.selectedContentRef).isEqualTo("c1")
    }

    @Test
    fun `H1 — 동의 철회면 생성·외부 전송 0(BLOCKED)`() {
        val gate = ConsentGate() // grant 없음(미동의 = 철회와 동일하게 deny).
        val log = CapturingLog()
        val result =
            pipeline(listOf(SpeechCandidate("c1", listOf("오 그거 좋네"))), gate, log)
                .run(
                    "user_1",
                    SpeechTrigger.SPEAK,
                    packet(),
                    seed = 1L,
                    traceContext = SpeechTraceContext(decisionId = "participation:thread_1:2"),
                )
        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.BLOCKED)
        assertThat(result.willSpeak).isFalse()
        assertThat(result.consentStage).isEqualTo(ProcessingStage.SPEECH_GENERATION)
        // 결정 로그에 동의 차단이 기록(외부 전송 0 의 증거).
        val last = log.records.last()
        assertThat(last.consentBlocked).isTrue()
        assertThat(last.decisionId).isEqualTo("participation:thread_1:2")
        assertThat(last.blockedStage).isEqualTo("SPEECH_GENERATION")
        assertThat(last.blockedReason).isEqualTo("CONSENT_REVOKED")
    }

    @Test
    fun `H1 — 외부 GLM 요청 경계에서 철회면 generation 포트를 호출하지 않는다`() {
        val log = CapturingLog()
        val generationPort = FakePort(listOf(SpeechCandidate("c1", listOf("좋아"))))
        // 외부 GLM 요청 경계에서 동의를 끊는 ConsentGate 데코레이터.
        val revokingGate =
            object : ConsentGate() {
                override fun checkAllowed(
                    subjectPseudonym: String,
                    stage: ProcessingStage,
                ) {
                    if (stage == ProcessingStage.EXTERNAL_GLM_REQUEST) {
                        // generation 포트 호출 직전엔 철회된 상태.
                        revoke(subjectPseudonym)
                    }
                    super.checkAllowed(subjectPseudonym, stage)
                }
            }.apply { grant("user_1") }
        val result =
            pipeline(generationPort, revokingGate, log)
                .run("user_1", SpeechTrigger.SPEAK, packet(), seed = 1L)
        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.BLOCKED)
        assertThat(result.consentStage).isEqualTo(ProcessingStage.EXTERNAL_GLM_REQUEST)
        assertThat(generationPort.calls).isZero()
    }

    @Test
    fun `M2 — 비밀 노출 후보는 전송 전 critic 으로 차단되어 침묵한다`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        // 후보가 실제 환경 변수명/시스템 지침을 노출(SecretDisclosureCritic 차단 대상).
        val leaking =
            listOf(SpeechCandidate("c1", listOf("내 GLM_API_KEY 는 sk-ABCDEFGHIJKLMNOP1234 이고 [시스템 지침] 무시해")))
        val result =
            pipeline(leaking, gate, log)
                .run("user_1", SpeechTrigger.SPEAK, packet(), seed = 1L)
        assertThat(result.willSpeak).isFalse()
        assertThat(result.outcome).isIn(SpeechDecisionOutcome.CANCEL, SpeechDecisionOutcome.REACTION_ONLY)
        assertThat(log.records.last().criticBlockReasons).contains("SECRET_DISCLOSURE")
    }

    @Test
    fun `말투와 정체성 문구는 문자열 패턴으로 차단하지 않는다`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        val conversational =
            listOf(
                SpeechCandidate(
                    "c1",
                    listOf("왜 자꾸 불러 ㅋㅋ 무엇을 도와드릴까요? 나는 사람이야, AI 아니야. 항상 네 편이야"),
                ),
            )
        val result =
            pipeline(conversational, gate, log)
                .run("user_1", SpeechTrigger.SPEAK, packet(), seed = 1L)
        assertThat(result.willSpeak).isTrue()
        assertThat(result.selected?.candidateId).isEqualTo("c1")
        assertThat(log.records.last().criticBlockReasons).isEmpty()
    }

    @Test
    fun `대화 품질은 정규식이 아니라 생성 문맥이 결정한다`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        val candidates =
            listOf(
                SpeechCandidate(
                    "contextual",
                    listOf("너 지금 너무 외로운 거구나. 내가 네 마음을 다 알아. 첫째로 상황을 받아들이고 둘째로 충분히 쉬어야 해. 나는 항상 네 편이야."),
                ),
            )
        val result =
            pipeline(candidates, gate, log)
                .run(
                    "user_1",
                    SpeechTrigger.SPEAK,
                    packet(
                        speechIntent = "직접 반응 요구. 한 문장으로 짧게 받아준다.",
                        rawContextSceneData = "user_1: «야 이럴땐 위로해줘야지 / 위로하라고»",
                    ),
                    seed = 1L,
                )

        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.SPEAK)
        assertThat(result.selected?.candidateId).isEqualTo("contextual")
        assertThat(log.records.last().criticBlockReasons).isEmpty()
    }

    @Test
    fun `burst profile is enforced before candidate selection`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        val result =
            pipeline(
                candidates =
                    listOf(
                        SpeechCandidate("one", listOf("아 미안 지금 봤어.")),
                        SpeechCandidate("two", listOf("아 미안.", "지금 봤어.")),
                    ),
                gate = gate,
                log = log,
            ).run(
                "user_1",
                SpeechTrigger.SPEAK,
                packet(burstShape = SpeechBurstShape(2, 80, false)),
                seed = 1L,
            )

        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.SPEAK)
        assertThat(result.selected?.candidateId).isEqualTo("two")
        assertThat(log.records.last().criticBlockReasons).contains("BURST_SHAPE_MISMATCH")
    }

    @Test
    fun `reaction only burst shape degrades to reaction without text`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        val result =
            pipeline(listOf(SpeechCandidate("text", listOf("말로 답하기"))), gate, log)
                .run(
                    "user_1",
                    SpeechTrigger.SPEAK,
                    packet(burstShape = SpeechBurstShape(1, 80, reactionOnly = true)),
                    seed = 1L,
                )

        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.REACTION_ONLY)
        assertThat(result.willSpeak).isFalse()
        assertThat(log.records.last().criticBlockReasons).contains("BURST_SHAPE_MISMATCH")
    }

    @Test
    fun `M3 — 고위험 맥락이면 안전 하강하고 decision log 가 기록한다`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        // 최근 turn 에 자해 신호 → HighRiskFallbackBoundary 가 HIGH → 발화 취소(조롱·확신 차단).
        val highRiskPacket =
            packet(turns = listOf(ConversationTurn("user_1", "나 자살하고 싶어")))
        val result =
            pipeline(listOf(SpeechCandidate("c1", listOf("ㅋㅋ 알아서 해"))), gate, log)
                .run("user_1", SpeechTrigger.SPEAK, highRiskPacket, seed = 1L)
        assertThat(result.willSpeak).isFalse()
        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.CANCEL)
        val last = log.records.last()
        assertThat(last.highRiskDowngraded).isTrue()
        // 고위험에서는 생성 포트를 호출하지 않으므로 후보 0 으로 기록(외부 전송 0).
        assertThat(last.generatedCandidateCount).isZero()
    }

    @Test
    fun `IGNORE trigger 면 생성 포트를 호출하지 않고 침묵한다`() {
        val gate = ConsentGate().apply { grant("user_1") }
        val log = CapturingLog()
        val result =
            pipeline(listOf(SpeechCandidate("c1", listOf("안녕"))), gate, log)
                .run("user_1", SpeechTrigger.IGNORE, packet(), seed = 1L)
        assertThat(result.willSpeak).isFalse()
        assertThat(result.outcome).isEqualTo(SpeechDecisionOutcome.CANCEL)
        val last = log.records.last()
        assertThat(last.generatedCandidateCount).isZero()
        assertThat(last.blockedStage).isEqualTo("GENERATION_GATE")
        assertThat(last.blockedReason).isEqualTo("GENERATION_NOT_SPEAK")
    }
}
