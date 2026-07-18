package com.discordassistant.central.platform.discord.admin

import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmResponseParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * OpenAI Responses tool calling 응답 순수 파서 검증(외부 서비스 불필요). 함수 호출 추출·일반 답변 폴백·
 * 깨진 arguments graceful·error/빈 응답 예외를 다룬다.
 */
class CloudLlmToolParsingTest {
    private val mapper = ObjectMapper()

    @Test
    fun `tool_calls 가 있으면 함수 호출로 파싱한다`() {
        val body =
            """
            {"output":[{"id":"fc_1","type":"function_call","call_id":"call_1",
              "name":"ban_member","arguments":"{\"userId\":\"123\",\"reason\":\"스팸\"}"}],
             "usage":{"input_tokens":10,"output_tokens":2}}
            """.trimIndent()
        val out = CloudLlmResponseParser.parseToolResponse(body, mapper)
        assertTrue(out.hasToolCalls)
        assertEquals(1, out.toolCalls.size)
        assertEquals("ban_member", out.toolCalls.first().name)
        assertEquals("call_1", out.toolCalls.first().id)
        assertEquals(10, out.usage.promptTokens)
        assertNull(out.text)
    }

    @Test
    fun `도구 호출이 없으면 일반 답변 텍스트로 파싱한다`() {
        val body = """{"output":[{"type":"message","content":[{"type":"output_text","text":"안녕하세요!"}]}]}"""
        val out = CloudLlmResponseParser.parseToolResponse(body, mapper)
        assertTrue(!out.hasToolCalls)
        assertEquals("안녕하세요!", out.text)
    }

    @Test
    fun `arguments 가 객체로 와도(문자열 아님) 그대로 보존한다`() {
        // 일부 호환 구현은 arguments 를 JSON 객체로 직접 넣기도 한다 — toString 으로 평탄화해 보존.
        val body =
            """{"output":[{"type":"function_call","name":"set_slowmode","arguments":{"channelId":"7","seconds":10}}]}"""
        val out = CloudLlmResponseParser.parseToolResponse(body, mapper)
        assertEquals("set_slowmode", out.toolCalls.first().name)
        assertTrue(
            out.toolCalls
                .first()
                .argumentsJson
                .contains("channelId"),
        )
    }

    @Test
    fun `error 응답은 일반화 예외로 던진다`() {
        val body = """{"error":{"message":"bad key"}}"""
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parseToolResponse(body, mapper) }
    }

    @Test
    fun `content 도 tool_calls 도 없으면 예외`() {
        val body = """{"output":[{"type":"message","content":[]}]}"""
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parseToolResponse(body, mapper) }
    }
}
