package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler
import com.discordassistant.central.global.privacy.ConsentGate
import com.discordassistant.central.platform.adapter.inbound.web.NexaLiveSpeechController
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
import com.discordassistant.central.speech.support.deterministicCompleteActionSelector
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * 어드민 "실제 발화 모드" API 검증. 생성까지만(shadow=true·sends=0)·SPEAK 시 생성 문장 노출·검증 실패(400).
 * 실제 GLM 대신 mock 포트(고정 후보)로 파이프라인을 구동한다(실 GLM·운영 배포 금지).
 * standaloneSetup 으로 advice 만 격리(보안 필터는 별도 [com.discordassistant.central.global.security] 가드 책임).
 */
class NexaLiveSpeechControllerTest {
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(NexaLiveSpeechController(liveService()))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    private fun liveService(): NexaLiveSpeechService {
        val gate = ConsentGate()
        val port =
            object : SpeechGenerationPort {
                override fun generate(request: SpeechGenerationRequest): SpeechGenerationResult =
                    SpeechGenerationResult(
                        candidates = listOf(SpeechCandidate("c-1", listOf("응 그 도커 캐시 builder prune 로 비우면 돼"))),
                        modelMetadata = "mock",
                    )
            }
        val pipeline =
            NexaSpeechPipelineService(
                consentGate = gate,
                generationGate =
                    SpeechGenerationGate(
                        CandidateGenerationService(
                            generationPort = port,
                            socialActCompiler = SocialActPromptCompiler(),
                            burstCompiler = BurstPromptCompiler(),
                            reasoningModeSelector = ReasoningModeSelector(),
                        ),
                    ),
                candidateSelector = NexaSpeechPipelineService.securityCriticSelector(),
                completeActionSelector = deterministicCompleteActionSelector(),
            )
        return NexaLiveSpeechService(gate, pipeline)
    }

    @Test
    fun `실제 발화 사전 정의 시나리오 목록을 반환한다`() {
        mockMvc
            .perform(get("/api/ai-network/nexa/sim/live-scenarios"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].scenarioId").exists())
    }

    @Test
    fun `실제 발화 실행은 shadow 이고 sends 가 0이며 생성 문장을 담는다`() {
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/live-scenarios/live-memory-docker/run"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shadow").value(true))
            .andExpect(jsonPath("$.sends").value(0))
            .andExpect(jsonPath("$.glmCalls").value(1))
            .andExpect(jsonPath("$.turns[0].spoke").value(true))
            .andExpect(jsonPath("$.turns[0].text").exists())
            .andExpect(jsonPath("$.turns[0].injectedTurns").exists())
    }

    @Test
    fun `직접 입력 멀티턴 시나리오를 실제 발화로 재생한다`() {
        val body =
            """
            {
              "scenarioId": "custom-live",
              "title": "직접 입력 라이브",
              "channelKind": "MEMBER",
              "seed": 1,
              "actors": [
                {"actorId": "a", "kind": "HUMAN", "displayName": "민수"},
                {"actorId": "n", "kind": "NEXA", "displayName": "니아"}
              ],
              "events": [
                {"seq": 1, "atOffsetMs": 0, "type": "MESSAGE_CREATE", "messageId": "m-1", "authorId": "a", "content": "도커 빌드가 캐시 때문에 안 돼"},
                {"seq": 2, "atOffsetMs": 70000, "type": "MESSAGE_CREATE", "messageId": "m-2", "authorId": "a", "content": "@니아 어떻게 풀어?", "mentionsNexa": true}
              ]
            }
            """.trimIndent()
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/run-live").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shadow").value(true))
            .andExpect(jsonPath("$.sends").value(0))
    }

    @Test
    fun `알 수 없는 사전 정의 id 는 400이다`() {
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/live-scenarios/nope/run"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `잘못된 직접 입력은 400이다`() {
        val body =
            """
            {"scenarioId": "bad", "channelKind": "MEMBER",
             "actors": [{"actorId": "a", "kind": "HUMAN"}],
             "events": [{"seq": 1, "atOffsetMs": 0, "type": "MESSAGE_CREATE", "messageId": "m-1", "authorId": "a", "content": "x"}]}
            """.trimIndent()
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/run-live").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
    }
}
