package com.discordassistant.central.socialmemory.adapter.outbound.extraction

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.ImageReview
import com.discordassistant.central.socialmemory.application.extraction.MemoryExtractionRequest
import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.extraction.CandidateKind
import com.discordassistant.central.socialmemory.domain.model.extraction.StatementModality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P07-T015 GLM 기반 추출 adapter. **routing CloudLlm 포트만** 쓰고 실제 외부 API 를 호출하지 않는다 —
 * 모든 테스트는 fake/recording CloudLlm 으로만 동작한다(provider-agent glm.py/Z.AI 타입 비참조).
 * acceptance: payload 최소화(원문 미전송)·schema validation(닫힌 enum 만 후보)·timeout 은 포트 위임.
 */
class CloudLlmMemoryCandidateExtractorTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val scope = VisibilityScope.Guild("g-1")

    private fun req() =
        MemoryExtractionRequest(
            sceneId = "scene-1",
            visibility = scope,
            participants = setOf("p-a"),
            extractionVersion = 3,
            consentGranted = true,
            observedCues = listOf("uses python"),
        )

    /** 응답 텍스트를 받아 돌려주는 fake CloudLlm(실제 HTTP 없음). prompt 를 캡처해 payload 최소화 검증. */
    private class FakeCloudLlm(
        private val enabled: Boolean,
        private val response: String,
    ) : CloudLlm {
        var lastPrompt: String? = null

        override fun isEnabled(): Boolean = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult {
            lastPrompt = prompt
            return CloudLlmResult(response)
        }

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = throw UnsupportedOperationException()

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = throw UnsupportedOperationException()
    }

    private class ThrowingCloudLlm : CloudLlm {
        override fun isEnabled() = true

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult = throw CloudLlmException("upstream down")

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = throw UnsupportedOperationException()

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = throw UnsupportedOperationException()
    }

    @Test
    fun `유효한 JSON 응답에서 닫힌 enum 후보만 추출하고 GLM 낮은 confidence 다`() {
        val json =
            """
            [
              {"kind":"TEMPORAL_FACT","subject":"p-a","predicate":"uses_language","object":"python","modality":"ASSERTED","sensitive":false},
              {"kind":"UNKNOWN_KIND","subject":"x","predicate":"y","object":"z"}
            ]
            """.trimIndent()
        val fake = FakeCloudLlm(enabled = true, response = json)
        val out = CloudLlmMemoryCandidateExtractor(fake, clock).extract(req())

        // schema validation: 모르는 kind 원소는 버려지고 닫힌 enum 후보 1개만.
        assertEquals(1, out.size)
        assertEquals(CandidateKind.TEMPORAL_FACT, out[0].kind)
        assertEquals(StatementModality.ASSERTED, out[0].modality)
        // GLM 단일 추출은 확정이 아니다(낮은 confidence, T010).
        assertFalse(out[0].confidence.isCertain)
        assertTrue(out[0].confidence.value < Confidence.CERTAIN_THRESHOLD)
        // provenance: scene 식별자가 출처로 운반된다(원문 아님).
        assertTrue(out[0].source.isSupportedBy("scene-1"))
    }

    @Test
    fun `payload 는 원문이 아니라 구조화 cue 와 닫힌 스키마 지시만 담는다`() {
        val fake = FakeCloudLlm(enabled = true, response = "[]")
        CloudLlmMemoryCandidateExtractor(fake, clock).extract(req())
        val prompt = fake.lastPrompt!!
        assertTrue(prompt.contains("JSON")) // 닫힌 스키마 지시
        assertTrue(prompt.contains("uses python")) // 구조화 cue
        assertFalse(prompt.contains("scene-1")) // 원천 ID 원문/식별자 자체를 프롬프트에 싣지 않는다(최소화)
    }

    @Test
    fun `CloudLlm 비활성이면 호출 없이 빈 후보`() {
        val fake = FakeCloudLlm(enabled = false, response = "[]")
        assertTrue(CloudLlmMemoryCandidateExtractor(fake, clock).extract(req()).isEmpty())
        assertEquals(null, fake.lastPrompt) // 비활성이면 generate 호출조차 안 함
    }

    @Test
    fun `호출 실패는 예외 없이 빈 후보로 흡수한다(consolidation 진행)`() {
        val out = CloudLlmMemoryCandidateExtractor(ThrowingCloudLlm(), clock).extract(req())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `깨진 JSON 응답은 빈 후보(스키마 미충족 저장 안 함)`() {
        val fake = FakeCloudLlm(enabled = true, response = "not json at all")
        assertTrue(CloudLlmMemoryCandidateExtractor(fake, clock).extract(req()).isEmpty())
    }
}
