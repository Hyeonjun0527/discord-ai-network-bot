package com.discordassistant.central.onboarding.application

import com.discordassistant.central.routing.application.ChannelTokenBudgetExceededException
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmPurpose
import com.discordassistant.central.routing.application.CloudLlmRequestOptions
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.renderNiaAskPrompt
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class NiaWebDemoInfo(
    val enabled: Boolean,
    val perUserWindowLimit: Int,
    val windowSeconds: Long,
    val messageMaxChars: Int,
)

enum class NiaWebDemoFailure {
    DISABLED,
    INVALID_CONVERSATION,
    INVALID_MESSAGE,
    BUSY,
    PER_MINUTE_LIMIT,
    PER_USER_WINDOW_LIMIT,
    GLOBAL_WINDOW_LIMIT,
    QUOTA_UNAVAILABLE,
    CLOUD_UNAVAILABLE,
    GENERATION_UNAVAILABLE,
}

sealed interface NiaWebDemoResult {
    data class Sent(
        val answer: String,
        val remaining: Int,
        val limit: Int,
    ) : NiaWebDemoResult

    data class Rejected(
        val reason: NiaWebDemoFailure,
    ) : NiaWebDemoResult
}

@Service
class NiaWebDemoService(
    private val cloudLlm: CloudLlm,
    private val promptSource: NiaPromptSource,
    private val quota: NiaWebDemoQuotaPort,
    private val conversations: NiaWebDemoConversationStore,
    @param:Value("\${central.nia-web-demo.enabled:false}") private val enabled: Boolean,
    @param:Value("\${central.nia-web-demo.per-minute:2}") private val perMinute: Int,
    @param:Value("\${central.nia-web-demo.per-user-window:5}") private val perUserWindow: Int,
    @param:Value("\${central.nia-web-demo.global-window:200}") private val globalWindow: Int,
    @param:Value("\${central.nia-web-demo.window-seconds:86400}") private val windowSeconds: Long,
    @param:Value("\${central.nia-web-demo.message-max-chars:500}") private val messageMaxChars: Int,
    @param:Value("\${central.nia-web-demo.history-turns:4}") private val historyTurns: Int,
    @param:Value("\${central.nia-web-demo.max-output-tokens:320}") private val maxOutputTokens: Int,
    @param:Value("\${central.nia-web-demo.timeout-seconds:15}") private val timeoutSeconds: Long,
    @param:Value("\${central.cloud.free-model:gpt-5.6-luna}") private val model: String,
) {
    private val inFlightUsers = ConcurrentHashMap.newKeySet<String>()
    private val limits =
        NiaWebDemoQuotaLimits(
            perMinute = perMinute,
            perUserWindow = perUserWindow,
            globalWindow = globalWindow,
            windowSeconds = windowSeconds,
        )

    init {
        require(messageMaxChars > 0) { "웹 체험 메시지 길이 상한은 양수여야 합니다." }
        require(historyTurns > 0) { "웹 체험 대화 문맥 턴 수는 양수여야 합니다." }
        require(maxOutputTokens > 0) { "웹 체험 출력 토큰 상한은 양수여야 합니다." }
        require(timeoutSeconds > 0) { "웹 체험 제한 시간은 양수여야 합니다." }
        require(model.isNotBlank()) { "웹 체험 모델은 비어 있을 수 없습니다." }
    }

    fun info(): NiaWebDemoInfo =
        NiaWebDemoInfo(
            enabled = enabled && cloudLlm.isEnabled(),
            perUserWindowLimit = perUserWindow,
            windowSeconds = windowSeconds,
            messageMaxChars = messageMaxChars,
        )

    fun send(
        userId: String,
        conversationId: String?,
        rawMessage: String?,
    ): NiaWebDemoResult {
        if (!enabled) return NiaWebDemoResult.Rejected(NiaWebDemoFailure.DISABLED)
        if (!cloudLlm.isEnabled()) return NiaWebDemoResult.Rejected(NiaWebDemoFailure.CLOUD_UNAVAILABLE)
        if (!userId.matches(DISCORD_USER_ID)) {
            return NiaWebDemoResult.Rejected(NiaWebDemoFailure.INVALID_MESSAGE)
        }
        val normalizedConversationId =
            conversationId
                ?.trim()
                ?.takeIf { it.matches(CONVERSATION_ID) }
                ?: return NiaWebDemoResult.Rejected(NiaWebDemoFailure.INVALID_CONVERSATION)
        val message =
            rawMessage
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= messageMaxChars && '\u0000' !in it }
                ?: return NiaWebDemoResult.Rejected(NiaWebDemoFailure.INVALID_MESSAGE)
        if (!inFlightUsers.add(userId)) return NiaWebDemoResult.Rejected(NiaWebDemoFailure.BUSY)
        return try {
            when (val quotaDecision = quota.tryConsume(userId, limits)) {
                NiaWebDemoQuotaDecision.PerMinuteExceeded ->
                    NiaWebDemoResult.Rejected(NiaWebDemoFailure.PER_MINUTE_LIMIT)
                NiaWebDemoQuotaDecision.PerUserWindowExceeded ->
                    NiaWebDemoResult.Rejected(NiaWebDemoFailure.PER_USER_WINDOW_LIMIT)
                NiaWebDemoQuotaDecision.GlobalWindowExceeded ->
                    NiaWebDemoResult.Rejected(NiaWebDemoFailure.GLOBAL_WINDOW_LIMIT)
                NiaWebDemoQuotaDecision.Unavailable ->
                    NiaWebDemoResult.Rejected(NiaWebDemoFailure.QUOTA_UNAVAILABLE)
                is NiaWebDemoQuotaDecision.Allowed ->
                    generate(
                        userId = userId,
                        conversationId = normalizedConversationId,
                        message = message,
                        remaining = quotaDecision.remaining,
                    )
            }
        } finally {
            inFlightUsers.remove(userId)
        }
    }

    private fun generate(
        userId: String,
        conversationId: String,
        message: String,
        remaining: Int,
    ): NiaWebDemoResult {
        val history = conversations.history(userId, conversationId, historyTurns * MESSAGES_PER_TURN)
        val prompt =
            promptSource.renderNiaAskPrompt(
                userMessage = message,
                relation = WEB_DEMO_CONTEXT,
            )
        val result =
            try {
                cloudLlm.generate(
                    prompt = prompt,
                    model = model,
                    history = history,
                    thinking = CloudThinking.DISABLED,
                    options =
                        CloudLlmRequestOptions(
                            purpose = CloudLlmPurpose.NIA_WEB_DEMO,
                            maxOutputTokens = maxOutputTokens,
                            requestTimeout = Duration.ofSeconds(timeoutSeconds),
                            maxRetries = 0,
                            channelTokenBudgetKey = GLOBAL_TOKEN_BUDGET_KEY,
                        ),
                )
            } catch (_: ChannelTokenBudgetExceededException) {
                return NiaWebDemoResult.Rejected(NiaWebDemoFailure.GLOBAL_WINDOW_LIMIT)
            } catch (_: CloudLlmException) {
                return NiaWebDemoResult.Rejected(NiaWebDemoFailure.GENERATION_UNAVAILABLE)
            }
        val answer =
            result.text
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return NiaWebDemoResult.Rejected(NiaWebDemoFailure.GENERATION_UNAVAILABLE)
        conversations.append(
            userId = userId,
            conversationId = conversationId,
            userMessage = message,
            assistantMessage = answer.take(MAX_STORED_ASSISTANT_CHARS),
            maxMessages = historyTurns * MESSAGES_PER_TURN,
        )
        return NiaWebDemoResult.Sent(answer, remaining, perUserWindow)
    }

    private companion object {
        val DISCORD_USER_ID = Regex("[0-9]{5,20}")
        val CONVERSATION_ID =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        const val MESSAGES_PER_TURN = 2
        const val MAX_STORED_ASSISTANT_CHARS = 4_000
        const val GLOBAL_TOKEN_BUDGET_KEY = "nia-web-demo-global"
        const val WEB_DEMO_CONTEXT =
            "[현재 환경] 웹 체험판의 텍스트 대화입니다. Discord 명령, 웹 검색, 파일과 이미지는 실행하거나 본 척하지 않습니다."
    }
}
