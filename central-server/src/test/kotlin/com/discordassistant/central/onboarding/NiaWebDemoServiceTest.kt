package com.discordassistant.central.onboarding

import com.discordassistant.central.onboarding.adapter.inbound.web.NiaWebDemoController
import com.discordassistant.central.onboarding.adapter.inbound.web.NiaWebDemoErrorResponse
import com.discordassistant.central.onboarding.adapter.inbound.web.NiaWebDemoMessageRequest
import com.discordassistant.central.onboarding.adapter.inbound.web.NiaWebDemoMessageResponse
import com.discordassistant.central.onboarding.adapter.outbound.quota.InMemoryNiaWebDemoQuotaAdapter
import com.discordassistant.central.onboarding.application.NiaWebDemoConversationStore
import com.discordassistant.central.onboarding.application.NiaWebDemoFailure
import com.discordassistant.central.onboarding.application.NiaWebDemoResult
import com.discordassistant.central.onboarding.application.NiaWebDemoService
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.routing.application.CloudToolResponse
import com.discordassistant.central.routing.application.CloudTurn
import com.discordassistant.central.routing.application.ImageReview
import com.discordassistant.central.shared.CodeNiaPromptSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NiaWebDemoServiceTest {
    @Test
    fun `웹 체험은 니아 프롬프트와 비용 상한을 한 번의 생성 호출에 적용한다`() {
        val cloud = RecordingCloudLlm()
        val service = service(cloud)

        val result = service.send(USER_ID, CONVERSATION_ID, "안녕 니아")

        assertThat(result).isEqualTo(NiaWebDemoResult.Sent("응, 안녕!", remaining = 4, limit = 5))
        assertThat(cloud.requests).hasSize(1)
        val request = cloud.requests.single()
        assertThat(request.prompt).contains("안녕 니아", "웹 체험판의 텍스트 대화")
        assertThat(request.history).isEmpty()
        assertThat(request.options.purpose.name).isEqualTo("NIA_WEB_DEMO")
        assertThat(request.options.maxOutputTokens).isEqualTo(320)
        assertThat(request.options.maxRetries).isEqualTo(0)
        assertThat(request.options.channelTokenBudgetKey).isEqualTo("nia-web-demo-global")
    }

    @Test
    fun `같은 대화만 이전 사용자와 니아 답변을 이어서 본다`() {
        val cloud = RecordingCloudLlm()
        val service = service(cloud)

        service.send(USER_ID, CONVERSATION_ID, "첫 질문")
        service.send(USER_ID, CONVERSATION_ID, "두 번째 질문")
        service.send(USER_ID, OTHER_CONVERSATION_ID, "새 대화")

        assertThat(cloud.requests[1].history)
            .containsExactly(
                CloudTurn("user", "첫 질문"),
                CloudTurn("assistant", "응, 안녕!"),
            )
        assertThat(cloud.requests[2].history).isEmpty()
    }

    @Test
    fun `입력 검증과 사용자 한도는 추가 AI 호출 전에 막는다`() {
        val cloud = RecordingCloudLlm()
        val service = service(cloud, perUserWindow = 1, perMinute = 10)

        assertThat(service.send(USER_ID, "not-a-uuid", "질문"))
            .isEqualTo(NiaWebDemoResult.Rejected(NiaWebDemoFailure.INVALID_CONVERSATION))
        assertThat(service.send(USER_ID, CONVERSATION_ID, " ".repeat(3)))
            .isEqualTo(NiaWebDemoResult.Rejected(NiaWebDemoFailure.INVALID_MESSAGE))
        assertThat(service.send(USER_ID, CONVERSATION_ID, "첫 질문"))
            .isInstanceOf(NiaWebDemoResult.Sent::class.java)
        assertThat(service.send(USER_ID, CONVERSATION_ID, "두 번째 질문"))
            .isEqualTo(NiaWebDemoResult.Rejected(NiaWebDemoFailure.PER_USER_WINDOW_LIMIT))
        assertThat(cloud.requests).hasSize(1)
    }

    @Test
    fun `기능 또는 클라우드가 꺼져 있으면 한도를 소비하지 않는다`() {
        val disabledCloud = RecordingCloudLlm(enabled = false)
        val cloudDisabled = service(disabledCloud)
        val featureDisabled = service(RecordingCloudLlm(), enabled = false)

        assertThat(cloudDisabled.send(USER_ID, CONVERSATION_ID, "질문"))
            .isEqualTo(NiaWebDemoResult.Rejected(NiaWebDemoFailure.CLOUD_UNAVAILABLE))
        assertThat(featureDisabled.send(USER_ID, CONVERSATION_ID, "질문"))
            .isEqualTo(NiaWebDemoResult.Rejected(NiaWebDemoFailure.DISABLED))
        assertThat(disabledCloud.requests).isEmpty()
    }

    @Test
    fun `컨트롤러는 Discord 로그인과 같은 출처 요청 표식을 모두 요구한다`() {
        val cloud = RecordingCloudLlm()
        val controller = NiaWebDemoController(service(cloud))
        val user =
            DefaultOAuth2User(
                listOf(SimpleGrantedAuthority("ROLE_USER")),
                mapOf("id" to USER_ID),
                "id",
            )

        assertThat(controller.status(null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(controller.status(user).statusCode).isEqualTo(HttpStatus.OK)
        val missingMarker = controller.send(user, null, NiaWebDemoMessageRequest(CONVERSATION_ID, "질문"))
        assertThat(missingMarker.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(missingMarker.body).isEqualTo(
            NiaWebDemoErrorResponse("invalid_request", "올바르지 않은 웹 체험 요청입니다."),
        )

        val sent = controller.send(user, "1", NiaWebDemoMessageRequest(CONVERSATION_ID, "질문"))

        assertThat(sent.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(sent.body).isEqualTo(NiaWebDemoMessageResponse("응, 안녕!", 4, 5))
    }

    private fun service(
        cloud: RecordingCloudLlm,
        enabled: Boolean = true,
        perMinute: Int = 10,
        perUserWindow: Int = 5,
    ): NiaWebDemoService =
        NiaWebDemoService(
            cloudLlm = cloud,
            promptSource = CodeNiaPromptSource,
            quota = InMemoryNiaWebDemoQuotaAdapter(),
            conversations =
                NiaWebDemoConversationStore(
                    ttlSeconds = 1800,
                    clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
                ),
            enabled = enabled,
            perMinute = perMinute,
            perUserWindow = perUserWindow,
            globalWindow = 200,
            windowSeconds = 86_400,
            messageMaxChars = 500,
            historyTurns = 4,
            maxOutputTokens = 320,
            timeoutSeconds = 15,
            model = "gpt-5.6-luna",
        )

    private data class RecordedRequest(
        val prompt: String,
        val history: List<CloudTurn>,
        val options: CloudLlmRequestOptions,
    )

    private class RecordingCloudLlm(
        private val enabled: Boolean = true,
    ) : CloudLlm {
        val requests = mutableListOf<RecordedRequest>()

        override fun isEnabled(): Boolean = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult = CloudLlmResult("응, 안녕!")

        override fun generate(
            prompt: String,
            model: String,
            history: List<CloudTurn>,
            thinking: CloudThinking?,
            options: CloudLlmRequestOptions,
        ): CloudLlmResult {
            requests += RecordedRequest(prompt, history, options)
            return CloudLlmResult("응, 안녕!")
        }

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): CloudToolResponse = error("사용하지 않는 경로")

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = error("사용하지 않는 경로")

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = error("사용하지 않는 경로")
    }

    private companion object {
        const val USER_ID = "123456789012345678"
        const val CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_CONVERSATION_ID = "22222222-2222-4222-8222-222222222222"
    }
}
