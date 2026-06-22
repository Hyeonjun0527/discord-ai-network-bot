package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler
import com.discordassistant.central.participation.application.sim.NexaSimulationService
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * 어드민 NEXA 시뮬레이션 API 검증. shadow only(shadow=true·sends=0)·사전 정의 실행·직접 입력·검증 실패(400).
 * standaloneSetup 으로 advice 만 격리(보안 필터는 별도 [com.discordassistant.central.global.security] 가드 책임).
 */
class NexaSimulationControllerTest {
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(NexaSimulationController(NexaSimulationService()))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `사전 정의 시나리오 목록을 반환한다`() {
        mockMvc
            .perform(get("/api/ai-network/nexa/sim/scenarios"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].scenarioId").exists())
    }

    @Test
    fun `사전 정의 시나리오 실행은 shadow 이고 sends 가 0이다`() {
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/scenarios/serious-direct-question/run"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shadow").value(true))
            .andExpect(jsonPath("$.sends").value(0))
            .andExpect(jsonPath("$.summary.speak").value(1))
            .andExpect(jsonPath("$.decisions[0].action").exists())
    }

    @Test
    fun `직접 입력 시나리오를 재생한다`() {
        val body =
            """
            {
              "scenarioId": "custom-1",
              "title": "직접 입력",
              "channelKind": "MEMBER",
              "seed": 1,
              "actors": [
                {"actorId": "a", "kind": "HUMAN"},
                {"actorId": "n", "kind": "NEXA"}
              ],
              "events": [
                {"seq": 1, "atOffsetMs": 0, "type": "MESSAGE_CREATE", "messageId": "m-1", "authorId": "a", "content": "안녕", "mentionsNexa": true},
                {"seq": 2, "atOffsetMs": 30000, "type": "MESSAGE_CREATE", "messageId": "m-2", "authorId": "a", "content": "뭐해"}
              ]
            }
            """.trimIndent()
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/run").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shadow").value(true))
            .andExpect(jsonPath("$.sends").value(0))
            .andExpect(jsonPath("$.summary.speak").value(1))
    }

    @Test
    fun `잘못된 시나리오 입력은 400이다`() {
        // NEXA actor 가 없는 시나리오 → SimScenarioException → 400.
        val body =
            """
            {
              "scenarioId": "bad",
              "channelKind": "MEMBER",
              "actors": [{"actorId": "a", "kind": "HUMAN"}],
              "events": [{"seq": 1, "atOffsetMs": 0, "type": "MESSAGE_CREATE", "messageId": "m-1", "authorId": "a", "content": "x"}]
            }
            """.trimIndent()
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/run").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `알 수 없는 사전 정의 id 는 400이다`() {
        mockMvc
            .perform(post("/api/ai-network/nexa/sim/scenarios/nope/run"))
            .andExpect(status().isBadRequest)
    }
}
