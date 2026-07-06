package com.discordassistant.central.speech.routing

import com.discordassistant.central.platform.discord.command.AskCommandHandler
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudLlmUsage
import com.discordassistant.central.routing.application.ImageReview
import com.discordassistant.central.speech.adapter.outbound.routing.CloudCallBudget
import com.discordassistant.central.speech.adapter.outbound.routing.RoutingCloudSpeechGenerationAdapter
import com.discordassistant.central.speech.adapter.outbound.routing.SpeechModelConfig
import com.discordassistant.central.speech.application.port.out.ReasoningMode
import com.discordassistant.central.speech.application.port.out.SpeechGenerationRequest
import com.discordassistant.central.speech.application.port.out.SpeechUsageRecorderPort
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P14-T002/T014: routing anti-corruption adapter — CloudLlm 포트로만 호출, glm/zai 타입 비노출,
 * fail-safe, stale 폐기, 사용량 기록.
 */
class RoutingCloudSpeechGenerationAdapterTest {
    private val config = SpeechModelConfig(model = "glm-5.1", timeoutSeconds = 8, maxRetries = 1)

    private fun request(count: Int = 1) =
        SpeechGenerationRequest(
            systemPrompt = "너는 니아야",
            userPrompt = "안녕",
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            candidateCount = count,
            reasoningMode = ReasoningMode.NONE,
            maxOutputTokens = 256,
        )

    /** content 를 정해진 대로 돌려주는 fake CloudLlm(필요 메서드만 의미 있게). */
    private open class FakeCloudLlm(
        private val enabled: Boolean = true,
        private val response: String = """{"candidates":[{"bubbles":["안녕!"]}]}""",
        private val throwOnce: Boolean = false,
    ) : CloudLlm {
        var calls = 0

        override fun isEnabled() = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult {
            calls++
            if (throwOnce && calls == 1) throw RuntimeException("transient")
            return CloudLlmResult(response, CloudLlmUsage(promptTokens = 10, completionTokens = 5))
        }

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): com.discordassistant.central.routing.application.CloudToolResponse = throw UnsupportedOperationException()

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
    fun `speech default model matches ask free cloud fast default`() {
        assertThat(SpeechModelConfig.DEFAULT_SPEECH_MODEL).isEqualTo(AskCommandHandler.DEFAULT_FREE_CLOUD_MODEL)
    }

    @Test
    fun `generates candidates via CloudLlm port and records usage`() {
        var recorded = false
        val recorder =
            object : SpeechUsageRecorderPort {
                override fun recordSpeechGeneration(
                    guildId: Long,
                    promptTokens: Int,
                    completionTokens: Int,
                    modelMetadata: String,
                ) {
                    recorded = true
                    assertThat(promptTokens).isEqualTo(10)
                    assertThat(modelMetadata).isEqualTo("glm-5.1")
                }
            }
        val adapter = RoutingCloudSpeechGenerationAdapter(FakeCloudLlm(), config, recorder)
        val result = adapter.generate(request())
        assertThat(result.isEmpty).isFalse()
        assertThat(result.candidates).hasSize(1)
        assertThat(result.modelMetadata).isEqualTo("glm-5.1")
        assertThat(recorded).isTrue()
    }

    @Test
    fun `disabled cloud llm yields empty result (fail-safe)`() {
        val adapter = RoutingCloudSpeechGenerationAdapter(FakeCloudLlm(enabled = false), config)
        assertThat(adapter.generate(request()).isEmpty).isTrue()
    }

    @Test
    fun `malformed response yields empty result without throwing`() {
        val adapter =
            RoutingCloudSpeechGenerationAdapter(FakeCloudLlm(response = "totally not json"), config)
        assertThat(adapter.generate(request()).isEmpty).isTrue()
    }

    @Test
    fun `transient failure is retried within budget`() {
        val fake = FakeCloudLlm(throwOnce = true)
        val adapter = RoutingCloudSpeechGenerationAdapter(fake, config)
        val result = adapter.generate(request())
        assertThat(result.isEmpty).isFalse()
        assertThat(fake.calls).isEqualTo(2) // 첫 호출 실패 후 재시도 성공.
    }

    @Test
    fun `stale response (deadline passed) is discarded`() {
        val now = Instant.parse("2026-06-22T00:00:00Z")
        // clock 을 deadline 이후로 고정해, 응답 직후 stale 판정이 나도록 한다.
        val lateClock = Clock.fixed(now.plusSeconds(100), ZoneOffset.UTC)
        val adapter = RoutingCloudSpeechGenerationAdapter(FakeCloudLlm(), config, clock = lateClock)
        val staleBudget = CloudCallBudget(Duration.ofSeconds(8), 0, now) // deadline 이미 지남.
        assertThat(adapter.generateWithin(request(), staleBudget).isEmpty).isTrue()
    }
}
