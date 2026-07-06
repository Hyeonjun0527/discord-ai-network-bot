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
    fun `업스트림 error 객체 → 사유를 노출한다(운영자 진단)`() {
        val body = """{"error":{"code":"1211","message":"model not found"}}"""
        val e = assertThrows(CloudLlmException::class.java) { CloudLlmResponseParser.parse(body, mapper) }
        // 원인(code·message)을 그대로 노출해 운영자가 z.ai 실패 이유를 바로 진단하게 한다.
        assertEquals("클라우드 AI 오류: 1211 model not found", e.message)
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

    // ── 이미지 안전 심사 파서(ADR 0006 단계2, glm.py parse_image_prompt_review 포팅) ──

    /** chat/completions 봉투 안에 심사 JSON 이 content 로 담겨온다. 코드펜스/잡텍스트는 첫 object 만 허용. */
    private fun reviewBody(content: String): String =
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                putArray("choices")
                    .addObject()
                    .putObject("message")
                    .put("role", "assistant")
                    .put("content", content)
            },
        )

    @Test
    fun `심사 allowed=true → 통과(category·reason 추출)`() {
        val r = CloudLlmResponseParser.parseImageReview(reviewBody("""{"allowed":true,"category":"safe","reason":"정상"}"""), mapper)
        assertEquals(true, r.allowed)
        assertEquals("safe", r.category)
        assertEquals("정상", r.reason)
    }

    @Test
    fun `심사 allowed=false → 차단(reason 보존)`() {
        val r =
            CloudLlmResponseParser.parseImageReview(
                reviewBody("""{"allowed":false,"category":"minor","reason":"미성년 성적 묘사"}"""),
                mapper,
            )
        assertEquals(false, r.allowed)
        assertEquals("minor", r.category)
        assertEquals("미성년 성적 묘사", r.reason)
    }

    @Test
    fun `심사 코드펜스로 감싼 JSON 도 파싱`() {
        val r = CloudLlmResponseParser.parseImageReview(reviewBody("```json\n{\"allowed\":true}\n```"), mapper)
        assertEquals(true, r.allowed)
        assertEquals("safe", r.category) // category 누락 시 allowed 에 따라 기본
        assertEquals("허용됨", r.reason)
    }

    @Test
    fun `심사 allowed 누락이면 fail-closed 예외(차단)`() {
        assertThrows(CloudLlmException::class.java) {
            CloudLlmResponseParser.parseImageReview(reviewBody("""{"category":"safe"}"""), mapper)
        }
    }

    @Test
    fun `심사 allowed 가 boolean 이 아니면 fail-closed 예외(차단)`() {
        assertThrows(CloudLlmException::class.java) {
            CloudLlmResponseParser.parseImageReview(reviewBody("""{"allowed":"yes"}"""), mapper)
        }
    }

    @Test
    fun `심사 content 가 JSON 이 아니면 fail-closed 예외(차단)`() {
        assertThrows(CloudLlmException::class.java) {
            CloudLlmResponseParser.parseImageReview(reviewBody("이건 JSON 이 아니에요"), mapper)
        }
    }
}
