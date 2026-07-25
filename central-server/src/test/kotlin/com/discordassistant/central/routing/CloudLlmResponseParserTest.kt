package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmResponseParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CloudLlmResponseParserTest {
    private val mapper = ObjectMapper()

    @Test
    fun `정상 Responses 응답에서 text와 usage를 추출한다`() {
        val body = responseBody("  안녕하세요  ", inputTokens = 12, outputTokens = 34)
        val result = CloudLlmResponseParser.parse(body, mapper)
        assertEquals("안녕하세요", result.text)
        assertEquals(12, result.usage.promptTokens)
        assertEquals(34, result.usage.completionTokens)
    }

    @Test
    fun `여러 output text는 순서대로 합친다`() {
        val body =
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"첫 줄"},{"type":"output_text","text":"둘째 줄"}]}]}"""
        assertEquals("첫 줄\n둘째 줄", CloudLlmResponseParser.parse(body, mapper).text)
    }

    @Test
    fun `completed status의 text는 정상 응답이다`() {
        val body =
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"완료"}]}]}"""

        assertEquals("완료", CloudLlmResponseParser.parse(body, mapper).text)
    }

    @Test
    fun `status가 없는 기존 Responses envelope도 계속 허용한다`() {
        assertEquals("호환", CloudLlmResponseParser.parse(responseBody("호환"), mapper).text)
    }

    @Test
    fun `incomplete status는 유효한 부분 text가 있어도 provider 실패다`() {
        val body =
            """{"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"부분 결과"}]}]}"""

        val error = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }

        assertEquals("클라우드 AI 응답이 완료되지 않았습니다.", error.message)
        assertFalse(error.message!!.contains("부분 결과"))
    }

    @Test
    fun `명시된 status는 completed 외에는 모두 provider 실패다`() {
        listOf("failed", "cancelled", "in_progress", "queued").forEach { status ->
            val body =
                """{"status":"$status","output":[{"type":"message","content":[{"type":"output_text","text":"노출 금지"}]}]}"""

            val error = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }

            assertEquals("클라우드 AI 응답이 완료되지 않았습니다.", error.message)
            assertFalse(error.message!!.contains(status))
            assertFalse(error.message!!.contains("노출 금지"))
        }
    }

    @Test
    fun `명시된 status가 null 또는 문자열이 아니어도 provider 실패다`() {
        listOf("null", "42", """{"state":"completed"}""").forEach { statusJson ->
            val body =
                """
                {
                  "status": $statusJson,
                  "output": [{"type": "message", "content": [{"type": "output_text", "text": "노출 금지"}]}]
                }
                """.trimIndent()

            val error = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }

            assertEquals("클라우드 AI 응답이 완료되지 않았습니다.", error.message)
            assertFalse(error.message!!.contains("노출 금지"))
        }
    }

    @Test
    fun `refusal은 다른 text가 있어도 본문을 노출하지 않는 provider 실패다`() {
        val refusalBody = "민감한 거부 사유"
        val body =
            """
            {
              "status": "completed",
              "output": [{
                "type": "message",
                "content": [
                  {"type": "output_text", "text": "부분 결과"},
                  {"type": "refusal", "refusal": "$refusalBody"}
                ]
              }]
            }
            """.trimIndent()

        val error = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }

        assertEquals("클라우드 AI가 요청 처리를 거부했습니다.", error.message)
        assertFalse(error.message!!.contains(refusalBody))
        assertThrows(CloudLlmException::class.java) {
            CloudLlmResponseParser.parseToolResponse(body, mapper)
        }
        assertEquals("[provider envelope redacted]", CloudLlmResponseParser.safeLogExcerpt(body, mapper))
    }

    @Test
    fun `미완료 status 응답은 운영 로그에서도 원문을 숨긴다`() {
        val body =
            """{"status":"incomplete","output":[{"content":[{"type":"output_text","text":"민감한 부분 결과"}]}]}"""

        assertEquals("[provider envelope redacted]", CloudLlmResponseParser.safeLogExcerpt(body, mapper))
    }

    @Test
    fun `usage가 없으면 0이다`() {
        val result = CloudLlmResponseParser.parse(responseBody("답"), mapper)
        assertEquals(0, result.usage.promptTokens)
        assertEquals(0, result.usage.completionTokens)
    }

    @Test
    fun `업스트림 error 객체는 사유를 노출한다`() {
        val body = """{"error":{"code":"model_not_found","message":"model not found"}}"""
        val error = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }
        assertEquals("클라우드 AI 오류: model_not_found model not found", error.message)
    }

    @Test
    fun `output이 비거나 JSON이 깨지면 실패한다`() {
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse("""{"output":[]}""", mapper) }
        assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse("not json", mapper) }
    }

    @Test
    fun `이미지 심사 allowed true와 false를 파싱한다`() {
        val allowed = CloudLlmResponseParser.parseImageReview(responseBody("""{"allowed":true,"category":"safe","reason":"정상"}"""), mapper)
        val denied = CloudLlmResponseParser.parseImageReview(responseBody("""{"allowed":false,"category":"minor","reason":"차단"}"""), mapper)
        assertEquals(true, allowed.allowed)
        assertEquals("정상", allowed.reason)
        assertEquals(false, denied.allowed)
        assertEquals("minor", denied.category)
    }

    @Test
    fun `이미지 심사는 코드펜스를 허용하지만 allowed 누락은 차단한다`() {
        val allowed = CloudLlmResponseParser.parseImageReview(responseBody("```json\n{\"allowed\":true}\n```"), mapper)
        assertEquals(true, allowed.allowed)
        assertEquals("safe", allowed.category)
        assertThrows(CloudLlmException::class.java) {
            CloudLlmResponseParser.parseImageReview(responseBody("""{"category":"safe"}"""), mapper)
        }
    }

    private fun responseBody(
        text: String,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    ): String {
        val root = mapper.createObjectNode()
        root
            .putArray(
                "output",
            ).addObject()
            .put("type", "message")
            .putArray("content")
            .addObject()
            .put("type", "output_text")
            .put("text", text)
        if (inputTokens != null || outputTokens != null) {
            root.putObject("usage").put("input_tokens", inputTokens ?: 0).put("output_tokens", outputTokens ?: 0)
        }
        return mapper.writeValueAsString(root)
    }
}
