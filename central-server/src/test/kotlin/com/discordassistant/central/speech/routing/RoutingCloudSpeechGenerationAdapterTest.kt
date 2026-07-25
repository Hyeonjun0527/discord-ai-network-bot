package com.discordassistant.central.speech.routing

import com.discordassistant.central.platform.discord.command.AskCommandHandler
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudLlmUsage
import com.discordassistant.central.routing.application.ImageReview
import com.discordassistant.central.shared.NiaPromptDefaults
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
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
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * NEXA-P14-T002/T014: routing anti-corruption adapter — CloudLlm 포트로만 호출, glm/zai 타입 비노출,
 * fail-safe, stale 폐기, 사용량 기록.
 */
class RoutingCloudSpeechGenerationAdapterTest {
    private val config = SpeechModelConfig(model = "glm-5.1", timeoutSeconds = 8, temperature = 0.9)

    private fun request(count: Int = 1) =
        SpeechGenerationRequest(
            systemPrompt = "너는 니아야",
            userPrompt = "안녕",
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            candidateCount = count,
            reasoningMode = ReasoningMode.NONE,
            maxOutputTokens = 256,
            stableSystemPromptChars = "너는 니아야".length,
        )

    /** content 를 정해진 대로 돌려주는 fake CloudLlm(필요 메서드만 의미 있게). */
    private open class FakeCloudLlm(
        private val enabled: Boolean = true,
        private val response: String = """{"candidates":[{"bubbles":["안녕!"]}]}""",
        private val responseSequence: List<String> = emptyList(),
        private val throwOnce: Boolean = false,
        private val throwAlways: Boolean = false,
    ) : CloudLlm {
        var calls = 0
        var lastPrompt: String? = null
        var lastOptions: CloudLlmRequestOptions? = null

        override fun isEnabled() = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult {
            calls++
            lastPrompt = prompt
            if (throwAlways) throw RuntimeException("persistent")
            if (throwOnce && calls == 1) throw RuntimeException("transient")
            val content = responseSequence.getOrElse(calls - 1) { response }
            return CloudLlmResult(content, CloudLlmUsage(promptTokens = 10, completionTokens = 5))
        }

        override fun generateSampled(
            prompt: String,
            model: String,
            temperature: Double,
            options: CloudLlmRequestOptions,
        ): CloudLlmResult {
            lastOptions = options
            return generate(prompt, model)
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
        val cloud = FakeCloudLlm()
        val adapter = RoutingCloudSpeechGenerationAdapter(cloud, config, recorder)
        val result = adapter.generate(request())
        assertThat(result.isEmpty).isFalse()
        assertThat(result.candidates).hasSize(1)
        assertThat(result.modelMetadata).isEqualTo("glm-5.1")
        assertThat(recorded).isTrue()
        assertThat(cloud.lastOptions!!.purpose).isEqualTo(CloudLlmPurpose.NIA_SPEECH)
        assertThat(cloud.lastOptions!!.maxOutputTokens).isEqualTo(256)
        val cachePolicy = cloud.lastOptions!!.cachePolicy
        assertThat(cloud.lastPrompt!!.take(cachePolicy.stablePrefixChars)).isEqualTo("너는 니아야\n\n")
        assertThat(cloud.lastOptions!!.requestTimeout).isEqualTo(Duration.ofMillis(7_750))
        assertThat(cloud.lastOptions!!.maxRetries).isZero()
    }

    @Test
    fun `관리형 combine 템플릿이 동적 값을 앞에 두면 cache write를 끈다`() {
        val managedTemplate =
            """
            {{toneDirective}}
            {{systemPrompt}}
            {{userPrompt}}
            """.trimIndent()
        val promptSource =
            NiaPromptSource {
                NiaPromptDefaults.documents +
                    (NiaPromptKey.SPEECH_COMBINE_TEMPLATE to managedTemplate)
            }
        val cloud = FakeCloudLlm()
        val adapter = RoutingCloudSpeechGenerationAdapter(cloud, config, promptSource = promptSource)

        adapter.generate(request().copy(toneDirective = "이번 장면의 동적 톤"))

        assertThat(cloud.lastOptions!!.cachePolicy.stablePrefixChars).isZero()
        assertThat(cloud.lastOptions!!.cachePolicy.key).isNull()
    }

    @Test
    fun `disabled cloud llm yields empty result (fail-safe)`() {
        val adapter = RoutingCloudSpeechGenerationAdapter(FakeCloudLlm(enabled = false), config)
        assertThat(adapter.generate(request()).isEmpty).isTrue()
    }

    @Test
    fun `malformed response yields empty result without throwing`() {
        val fake = FakeCloudLlm(response = "totally not json")
        val adapter =
            RoutingCloudSpeechGenerationAdapter(fake, config)

        assertThat(adapter.generate(request()).isEmpty).isTrue()
        assertThat(fake.calls).isEqualTo(2)
    }

    @Test
    fun `transient failure is retried once`() {
        val fake = FakeCloudLlm(throwOnce = true)
        val adapter = RoutingCloudSpeechGenerationAdapter(fake, config)
        val result = adapter.generate(request())

        assertThat(result.isEmpty).isFalse()
        assertThat(fake.calls).isEqualTo(2)
        assertThat(fake.lastOptions!!.maxRetries).isZero()
    }

    @Test
    fun `malformed first response is retried once`() {
        val fake =
            FakeCloudLlm(
                responseSequence =
                    listOf(
                        "totally not json",
                        """{"candidates":[{"bubbles":["다시 성공"]}]}""",
                    ),
            )
        val adapter = RoutingCloudSpeechGenerationAdapter(fake, config)

        val result = adapter.generate(request())

        assertThat(result.isEmpty).isFalse()
        assertThat(result.candidates.single().bubbles).containsExactly("다시 성공")
        assertThat(fake.calls).isEqualTo(2)
    }

    @Test
    fun `persistent provider failure stops after two attempts`() {
        val fake = FakeCloudLlm(throwAlways = true)
        val adapter = RoutingCloudSpeechGenerationAdapter(fake, config)

        assertThat(adapter.generate(request()).isEmpty).isTrue()
        assertThat(fake.calls).isEqualTo(2)
    }

    @Test
    fun `speech does not start its retry after the overall deadline`() {
        val startedAt = Instant.parse("2026-06-22T00:00:00Z")
        val deadline = startedAt.plusSeconds(8)
        val clock = SequenceClock(listOf(startedAt, deadline))
        val fake = FakeCloudLlm(throwAlways = true)
        val adapter = RoutingCloudSpeechGenerationAdapter(fake, config, clock = clock)

        val result = adapter.generateWithin(request(), CloudCallBudget(Duration.ofSeconds(8), deadline))

        assertThat(result.isEmpty).isTrue()
        assertThat(fake.calls).isEqualTo(1)
    }

    @Test
    fun `stale response (deadline passed) is discarded`() {
        val now = Instant.parse("2026-06-22T00:00:00Z")
        // clock 을 deadline 이후로 고정해, 응답 직후 stale 판정이 나도록 한다.
        val lateClock = Clock.fixed(now.plusSeconds(100), ZoneOffset.UTC)
        val fake = FakeCloudLlm()
        val adapter = RoutingCloudSpeechGenerationAdapter(fake, config, clock = lateClock)
        val staleBudget = CloudCallBudget(Duration.ofSeconds(8), now) // deadline 이미 지남.

        assertThat(adapter.generateWithin(request(), staleBudget).isEmpty).isTrue()
        assertThat(fake.calls).isZero()
    }

    private class SequenceClock(
        instants: List<Instant>,
    ) : Clock() {
        private val remaining = ArrayDeque(instants)
        private var latest = instants.first()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant {
            if (remaining.isNotEmpty()) latest = remaining.removeFirst()
            return latest
        }
    }
}
