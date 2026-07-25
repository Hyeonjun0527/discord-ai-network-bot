package com.discordassistant.central.participation.adapter.outbound.judge

import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmRequest
import com.discordassistant.central.participation.application.port.out.NiaJudgeOutputContract
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudLlmUsage
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.routing.application.CloudToolResponse
import com.discordassistant.central.routing.application.CloudTurn
import com.discordassistant.central.routing.application.ImageReview
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class CloudLlmNiaJudgeAdapterTest {
    @Test
    fun `deterministic cloud completion maps provider-neutral judge response`() {
        val cloudLlm = RecordingCloudLlm()
        val adapter = CloudLlmNiaJudgeAdapter(cloudLlm, "gpt-5.6-luna")
        val request = request()

        val response = adapter.complete(request)

        assertThat(cloudLlm.prompt).isEqualTo(request.prompt)
        assertThat(cloudLlm.model).isEqualTo("gpt-5.6-luna")
        assertThat(cloudLlm.history).isEmpty()
        assertThat(cloudLlm.thinking).isEqualTo(CloudThinking.DISABLED)
        assertThat(cloudLlm.options!!.purpose).isEqualTo(CloudLlmPurpose.NIA_JUDGE)
        assertThat(cloudLlm.options!!.maxOutputTokens).isEqualTo(2_048)
        assertThat(cloudLlm.options!!.jsonSchema).isNull()
        assertThat(cloudLlm.options!!.cachePolicy!!.stablePrefixChars).isEqualTo(request.stablePromptPrefixChars)
        assertThat(cloudLlm.options!!.requestTimeout).isEqualTo(Duration.ofMillis(750))
        assertThat(cloudLlm.options!!.maxRetries).isZero()
        assertThat(response.content).isEqualTo(JUDGE_JSON)
        assertThat(response.modelVersion).isEqualTo("gpt-5.6-luna")
        assertThat(response.finishReason).isEqualTo(CloudLlmNiaJudgeAdapter.FINISH_REASON_COMPLETED)
        assertThat(response.promptTokens).isEqualTo(17)
        assertThat(response.completionTokens).isEqualTo(9)
        assertThat(response.latencyMillis).isNotNull().isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `disabled cloud fails before invoking provider`() {
        val cloudLlm = RecordingCloudLlm(enabled = false)

        assertThatThrownBy { CloudLlmNiaJudgeAdapter(cloudLlm).complete(request()) }
            .isInstanceOf(CloudLlmException::class.java)
            .hasMessageContaining("비활성")
        assertThat(cloudLlm.calls).isZero()
    }

    @Test
    fun `모호한 장면 metadata가 있어도 reasoning은 none으로 유지한다`() {
        val cloudLlm = RecordingCloudLlm()
        val adapter = CloudLlmNiaJudgeAdapter(cloudLlm, "gpt-5.6-luna")

        adapter.complete(request().copy(metadata = mapOf("reasoning_mode" to "deliberate")))

        assertThat(cloudLlm.thinking).isEqualTo(CloudThinking.DISABLED)
    }

    @Test
    fun `shadow와 repair 호출은 운영 비용 목적이 따로 기록된다`() {
        val cloudLlm = RecordingCloudLlm()
        val adapter = CloudLlmNiaJudgeAdapter(cloudLlm, "gpt-5.6-luna")

        adapter.complete(request().copy(metadata = mapOf("execution_purpose" to "shadow")))
        assertThat(cloudLlm.options!!.purpose).isEqualTo(CloudLlmPurpose.NIA_SHADOW_JUDGE)

        adapter.complete(
            request().copy(
                metadata = mapOf("execution_purpose" to "shadow", "repair_attempt" to "true"),
            ),
        )
        assertThat(cloudLlm.options!!.purpose).isEqualTo(CloudLlmPurpose.NIA_SHADOW_JUDGE_REPAIR)

        adapter.complete(request().copy(metadata = mapOf("repair_attempt" to "true")))
        assertThat(cloudLlm.options!!.purpose).isEqualTo(CloudLlmPurpose.NIA_JUDGE_REPAIR)
    }

    @Test
    fun `Spring property가 structured output flag를 주입하고 기본값은 false다`() {
        val enabledCloudLlm = RecordingCloudLlm()
        contextRunner(enabledCloudLlm)
            .withPropertyValues("central.nexa.participation.judge.structured-output-enabled=true")
            .run { context ->
                assertThat(context.startupFailure).isNull()
                context.getBean(CloudLlmNiaJudgeAdapter::class.java).complete(request())
                assertThat(enabledCloudLlm.options!!.jsonSchema).isNotNull()
            }

        val defaultCloudLlm = RecordingCloudLlm()
        contextRunner(defaultCloudLlm).run { context ->
            assertThat(context.startupFailure).isNull()
            context.getBean(CloudLlmNiaJudgeAdapter::class.java).complete(request())
            assertThat(defaultCloudLlm.options!!.jsonSchema).isNull()
        }
    }

    @Test
    fun `structured output flag는 모든 judge 목적에 동일한 계약을 적용한다`() {
        val cloudLlm = RecordingCloudLlm()
        val adapter =
            CloudLlmNiaJudgeAdapter(
                cloudLlm = cloudLlm,
                model = "gpt-5.6-luna",
                structuredOutputEnabled = true,
            )
        val cases =
            mapOf(
                emptyMap<String, String>() to CloudLlmPurpose.NIA_JUDGE,
                mapOf("execution_purpose" to "shadow") to CloudLlmPurpose.NIA_SHADOW_JUDGE,
                mapOf("repair_attempt" to "true") to CloudLlmPurpose.NIA_JUDGE_REPAIR,
                mapOf(
                    "execution_purpose" to "shadow",
                    "repair_attempt" to "true",
                ) to CloudLlmPurpose.NIA_SHADOW_JUDGE_REPAIR,
            )

        cases.forEach { (metadata, purpose) ->
            adapter.complete(request().copy(metadata = metadata))

            assertThat(cloudLlm.options!!.purpose).isEqualTo(purpose)
            assertThat(cloudLlm.options!!.jsonSchema!!.name).isEqualTo(NiaJudgeOutputContract.FORMAT_NAME)
            assertThat(cloudLlm.options!!.jsonSchema!!.schemaJson).isEqualTo(NiaJudgeOutputContract.JSON_SCHEMA)
        }
    }

    @Test
    fun `structured output flag가 꺼지면 모든 judge 목적에서 계약을 보내지 않는다`() {
        val cloudLlm = RecordingCloudLlm()
        val adapter =
            CloudLlmNiaJudgeAdapter(
                cloudLlm = cloudLlm,
                model = "gpt-5.6-luna",
                structuredOutputEnabled = false,
            )
        val metadataByPurpose =
            listOf(
                emptyMap(),
                mapOf("execution_purpose" to "shadow"),
                mapOf("repair_attempt" to "true"),
                mapOf("execution_purpose" to "shadow", "repair_attempt" to "true"),
            )

        metadataByPurpose.forEach { metadata ->
            adapter.complete(request().copy(metadata = metadata))

            assertThat(cloudLlm.options!!.jsonSchema).isNull()
        }
    }

    @Test
    fun `structured output provider 실패는 비구조화 요청으로 재시도하지 않는다`() {
        val cloudLlm =
            RecordingCloudLlm {
                throw CloudLlmException("structured output failed")
            }
        val adapter =
            CloudLlmNiaJudgeAdapter(
                cloudLlm = cloudLlm,
                structuredOutputEnabled = true,
            )

        assertThatThrownBy { adapter.complete(request()) }
            .isInstanceOf(CloudLlmException::class.java)
            .hasMessage("structured output failed")
        assertThat(cloudLlm.calls).isEqualTo(1)
        assertThat(cloudLlm.options!!.jsonSchema).isNotNull()
        assertThat(cloudLlm.options!!.maxRetries).isZero()
    }

    @Test
    fun `request timeout cancels slow cloud call`() {
        val cloudLlm =
            RecordingCloudLlm {
                Thread.sleep(5_000)
                RESULT
            }

        assertThatThrownBy {
            CloudLlmNiaJudgeAdapter(cloudLlm).complete(request(timeoutMillis = 50))
        }.isInstanceOf(CloudLlmException::class.java)
            .hasMessageContaining("시간이 초과")
    }

    private fun request(timeoutMillis: Long = 1_000): NiaJudgeLlmRequest =
        NiaJudgeLlmRequest(
            prompt = "fixed judge rules\ndynamic scene",
            promptVersion = "nia-judge-prompt-v1",
            seed = 42L,
            timeoutMillis = timeoutMillis,
            stablePromptPrefixChars = "fixed judge rules\n".length,
        )

    private fun contextRunner(cloudLlm: CloudLlm): ApplicationContextRunner =
        ApplicationContextRunner()
            .withBean(CloudLlm::class.java, { cloudLlm })
            .withBean(CloudLlmNiaJudgeAdapter::class.java)

    private class RecordingCloudLlm(
        private val enabled: Boolean = true,
        private val completion: () -> CloudLlmResult = { RESULT },
    ) : CloudLlm {
        var calls: Int = 0
        var prompt: String? = null
        var model: String? = null
        var history: List<CloudTurn>? = null
        var thinking: CloudThinking? = null
        var options: CloudLlmRequestOptions? = null

        override fun isEnabled(): Boolean = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult = error("멀티턴 판단 경로만 사용해야 합니다.")

        override fun generate(
            prompt: String,
            model: String,
            history: List<CloudTurn>,
            thinking: CloudThinking?,
        ): CloudLlmResult {
            calls++
            this.prompt = prompt
            this.model = model
            this.history = history
            this.thinking = thinking
            return completion()
        }

        override fun generate(
            prompt: String,
            model: String,
            history: List<CloudTurn>,
            thinking: CloudThinking?,
            options: CloudLlmRequestOptions,
        ): CloudLlmResult {
            this.options = options
            return generate(prompt, model, history, thinking)
        }

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): CloudToolResponse = error("사용하지 않는 경로입니다.")

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = error("사용하지 않는 경로입니다.")

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = error("사용하지 않는 경로입니다.")
    }

    companion object {
        private const val JUDGE_JSON =
            "{\"schema\":\"nia.participation-judge-output.v1\",\"action\":\"IGNORE\"}"
        private val RESULT =
            CloudLlmResult(
                text = JUDGE_JSON,
                usage = CloudLlmUsage(promptTokens = 17, completionTokens = 9),
            )
    }
}
