package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.participation.domain.service.sim.SimActor
import com.discordassistant.central.participation.domain.service.sim.SimActorKind
import com.discordassistant.central.participation.domain.service.sim.SimChannelKind
import com.discordassistant.central.participation.domain.service.sim.SimEvent
import com.discordassistant.central.participation.domain.service.sim.SimEventType
import com.discordassistant.central.participation.domain.service.sim.SimScenario
import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.generation.CandidateGenerationService
import com.discordassistant.central.speech.application.generation.ReasoningModeSelector
import com.discordassistant.central.speech.application.generation.SpeechGenerationGate
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationPort
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.application.prompt.BurstPromptCompiler
import com.discordassistant.central.speech.application.prompt.SocialActPromptCompiler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 어드민 "실제 발화 모드" 서비스 검증(전송 0·SPEAK 시에만 GLM·멀티턴 컨텍스트 주입).
 *
 * 실제 GLM 대신 후보를 세는 mock [SpeechGenerationPort] 로 파이프라인을 구동한다(실 GLM·운영 배포 금지).
 * 다음을 실제 경로([NexaSpeechPipelineService] → 실제 [CandidateGenerationService]·critic·[ConsentGate])에서 검증:
 *  - SPEAK 결정에서만 generation 포트가 호출된다(IGNORE/REACT → 0).
 *  - 생성된 문장이 결과 turn 에 실린다(전송이 아니라 생성).
 *  - sends 는 항상 0(생성까지만).
 *  - prompt 에 이전 turn 들이 컨텍스트로 주입된다(멀티턴 — userPrompt 에 앞 발언 포함).
 */
class NexaLiveSpeechServiceTest {
    /** 호출 횟수와 마지막 요청을 기록하는 mock 포트(외부 GLM 미호출). */
    private class CountingPort(
        private val bubbles: List<String> = listOf("아 아까 그 도커 얘기? builder prune 로 캐시 비워봐"),
    ) : SpeechGenerationPort {
        var calls = 0
        var lastRequest: SpeechGenerationRequest? = null

        override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult {
            calls++
            lastRequest = request
            return SpeechGenerationResult(
                candidates = listOf(SpeechCandidate(candidateId = "c-$calls", bubbles = bubbles)),
                modelMetadata = "mock",
            )
        }
    }

    private fun serviceWith(port: SpeechGenerationPort): Pair<NexaLiveSpeechService, ConsentGate> {
        val gate = ConsentGate()
        val generationService =
            CandidateGenerationService(
                generationPort = port,
                socialActCompiler = SocialActPromptCompiler(),
                burstCompiler = BurstPromptCompiler(),
                reasoningModeSelector = ReasoningModeSelector(),
            )
        val pipeline =
            NexaSpeechPipelineService(
                consentGate = gate,
                generationGate = SpeechGenerationGate(generationService),
                candidateSelector = NexaSpeechPipelineService.securityCriticSelector(),
            )
        return NexaLiveSpeechService(gate, pipeline) to gate
    }

    @Test
    fun `사전 정의 멀티턴 데모는 SPEAK 에서만 GLM 을 호출하고 전송은 0이다`() {
        val port = CountingPort()
        val (service, _) = serviceWith(port)

        val result = service.runPredefined("live-memory-docker")

        assertThat(result.sends).isEqualTo(0)
        assertThat(result.shadow).isTrue()
        // SPEAK 결정 수 == 실제 GLM 호출 수 == turn 수.
        assertThat(result.glmCalls).isEqualTo(result.speakDecisions)
        assertThat(port.calls).isEqualTo(result.glmCalls)
        assertThat(result.glmCalls).isGreaterThanOrEqualTo(1)
        // 생성된 문장이 turn 에 실린다(전송이 아니라 생성).
        val spoken = result.turns.first { it.spoke }
        assertThat(spoken.text).isNotBlank()
        // 멀티턴: 이전 turn 들이 컨텍스트로 주입됐다(앞 발언 ≥ 2개).
        assertThat(spoken.injectedTurns).isGreaterThanOrEqualTo(2)
        // 기억도 주입됐다.
        assertThat(spoken.injectedMemory).isNotEmpty()
    }

    @Test
    fun `이전 turn 원문이 GLM userPrompt 에 컨텍스트로 들어간다`() {
        val port = CountingPort()
        val (service, _) = serviceWith(port)

        service.runPredefined("live-memory-docker")

        val userPrompt = port.lastRequest?.userPrompt.orEmpty()
        // 앞 turn(도커 캐시 문제)이 발화 생성 프롬프트에 컨텍스트로 들어가야 한다.
        assertThat(userPrompt).contains("도커")
    }

    @Test
    fun `호명 없는 침묵 시나리오는 GLM 을 호출하지 않는다`() {
        val port = CountingPort()
        val (service, _) = serviceWith(port)
        val silent =
            SimScenario(
                scenarioId = "silent",
                title = "조용한 서버",
                channelKind = SimChannelKind.MEMBER,
                seed = 16001,
                actors = listOf(SimActor("a", SimActorKind.HUMAN, "지호"), SimActor("n", SimActorKind.NEXA, "니아")),
                events =
                    listOf(
                        SimEvent(1, 0, SimEventType.MESSAGE_CREATE, messageId = "m-1", authorId = "a", content = "점심 뭐 먹지"),
                        SimEvent(2, 30_000, SimEventType.MESSAGE_CREATE, messageId = "m-2", authorId = "a", content = "ㅋㅋ"),
                    ),
            )

        val result = service.run(silent)

        assertThat(result.sends).isEqualTo(0)
        assertThat(result.glmCalls).isEqualTo(0)
        assertThat(port.calls).isEqualTo(0)
        assertThat(result.turns).isEmpty()
    }

    @Test
    fun `GLM 이 빈 결과를 주면 전송 0으로 안전 하강한다`() {
        val emptyPort =
            object : SpeechGenerationPort {
                override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult = SpeechGenerationResult.EMPTY
            }
        val (service, _) = serviceWith(emptyPort)

        val result = service.runPredefined("live-memory-deploy")

        assertThat(result.sends).isEqualTo(0)
        // 발화 문장 없음(안전 하강) — 전송은 여전히 0.
        assertThat(result.turns.none { it.spoke }).isTrue()
    }
}
