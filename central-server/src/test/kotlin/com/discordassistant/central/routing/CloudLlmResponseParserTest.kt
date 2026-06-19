package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmResponseParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** z.ai(OpenAI 호환) 응답 파서 순수 단위테스트 — 외부 호출 없이 추출/실패 분기 고정. */
class CloudLlmResponseParserTest {
    private val mapper = ObjectMapper()

    @Test
    fun `정상 응답 → content 와 usage 추출`() {
        val body =
            """
            {"choices":[{"message":{"role":"assistant","content":"  안녕하세요  "}}],
             "usage":{"prompt_tokens":12,"completion_tokens":34}}
            """.trimIndent()
        val r = CloudLlmResponseParser.parse(body, mapper)
        assertEquals("안녕하세요", r.text) // trim 적용
        assertEquals(12, r.usage.promptTokens)
        assertEquals(34, r.usage.completionTokens)
    }

    @Test
    fun `usage 없으면 0 으로`() {
        val body = """{"choices":[{"message":{"content":"답"}}]}"""
        val r = CloudLlmResponseParser.parse(body, mapper)
        assertEquals("답", r.text)
        assertEquals(0, r.usage.promptTokens)
        assertEquals(0, r.usage.completionTokens)
    }

    @Test
    fun `업스트림 error 객체 → 일반화 예외`() {
        val body = """{"error":{"message":"invalid api key"}}"""
        val e = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }
        assertEquals(CloudLlmResponseParser.USER_ERROR_MESSAGE, e.message)
    }

    @Test
    fun `choices 비었으면 예외`() {
        val body = """{"choices":[]}"""
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }
    }

    @Test
    fun `content 빈 문자열이면 예외(안전 필터 차단)`() {
        val body = """{"choices":[{"message":{"content":""}}]}"""
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }
    }

    @Test
    fun `깨진 JSON 이면 파싱 실패 예외`() {
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse("not json", mapper) }
    }
}
