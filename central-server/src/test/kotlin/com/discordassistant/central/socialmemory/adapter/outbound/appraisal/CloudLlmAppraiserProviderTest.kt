package com.discordassistant.central.socialmemory.adapter.outbound.appraisal

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.routing.application.CloudToolResponse
import com.discordassistant.central.routing.application.CloudTurn
import com.discordassistant.central.routing.application.ImageReview
import com.discordassistant.central.socialmemory.domain.model.appraisal.AppraisalCertainty
import com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** GLM 어댑터 검증 — 비활성·정상·예외 모두 graceful(보수 폴백). 도메인 parse 는 SocialAppraiserTest 가 검증. */
class CloudLlmAppraiserProviderTest {
    private val lens = RelationshipLens.fromAxes(0.3, 0.2, 0.15, 0.25)
    private val msg = listOf("니아 나쁜 여자야")

    private class FakeCloudLlm(
        private val enabled: Boolean = true,
        private val text: String = "",
        private val fail: Boolean = false,
    ) : CloudLlm {
        var lastThinking: CloudThinking? = null
            private set

        override fun isEnabled() = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult {
            if (fail) throw RuntimeException("boom")
            return CloudLlmResult(text)
        }

        override fun generate(
            prompt: String,
            model: String,
            history: List<CloudTurn>,
            thinking: CloudThinking?,
        ): CloudLlmResult {
            lastThinking = thinking
            return generate(prompt, model)
        }

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): CloudToolResponse = throw NotImplementedError()

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = throw NotImplementedError()

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = throw NotImplementedError()
    }

    @Test
    fun `클라우드 비활성이면 보수 폴백(LOW)`() {
        val a = CloudLlmAppraiserProvider(FakeCloudLlm(enabled = false)).appraise(msg, "discord:1", lens)
        assertEquals(AppraisalCertainty.LOW, a.certainty)
        assertEquals("cloud-llm-disabled", a.error)
    }

    @Test
    fun `정상 GLM JSON 을 등급으로 파싱`() {
        val json = """{"target_is_nia":true,"kind":"INSULT","intensity":"CLEAR","certainty":"CLEAR"}"""
        val cloudLlm = FakeCloudLlm(text = json)
        val a = CloudLlmAppraiserProvider(cloudLlm).appraise(msg, "discord:1", lens)
        assertEquals(SocialEventKind.INSULT, a.kind)
        assertEquals(AppraisalCertainty.CLEAR, a.certainty)
        assertEquals(CloudThinking.DISABLED, cloudLlm.lastThinking)
    }

    @Test
    fun `호출 예외는 보수 폴백으로 표면화`() {
        val a = CloudLlmAppraiserProvider(FakeCloudLlm(fail = true)).appraise(msg, "discord:1", lens)
        assertEquals(AppraisalCertainty.LOW, a.certainty)
        assertEquals(SocialEventKind.SMALLTALK, a.kind)
    }
}
