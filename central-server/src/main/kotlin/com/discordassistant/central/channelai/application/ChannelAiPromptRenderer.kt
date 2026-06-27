package com.discordassistant.central.channelai.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.shared.ContentSafety
import com.discordassistant.central.shared.NexaIdentity
import org.springframework.stereotype.Component

/**
 * 채널 AI 시스템/유저 프롬프트 미리보기 렌더링 — 읽기 전용 협력자(@Transactional·write 없음).
 *
 * 활성/최신 behavior 를 조회해 프롬프트를 합성한다. 조회만 하므로 호출자 TX 문맥에서 그대로 동작하며
 * 별 빈으로 빼도 새 TX 가 열리지 않는다(@Transactional 미부여). 프롬프트 문구·민감 판정 정규식은
 * 추출 전과 1바이트 불변.
 */
@Component
class ChannelAiPromptRenderer(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun promptPreview(
        guildId: Long,
        channelId: Long,
        userQuestion: String,
        ragContextText: String? = null,
    ): ChannelAiPromptPreview {
        featureGate.requireChannelAiEnabled()
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behavior =
            channelAi?.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: channelAi?.let { versions.findTopByChannelAiIdOrderByVersionDesc(it.id) }
        val name = channelAi?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: "니아"
        val purpose = behavior?.purpose ?: DEFAULT_CHANNEL_AI_PURPOSE
        val tone = behavior?.tone ?: DEFAULT_CHANNEL_AI_TONE
        val answerLength = behavior?.answerLength ?: DEFAULT_CHANNEL_AI_ANSWER_LENGTH
        val constitution = behavior?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION
        val customInstruction = behavior?.customInstruction?.trim()?.takeIf { it.isNotBlank() }
        val sensitive = userQuestion.looksSensitive()
        val sanitizedQuestion = userQuestion.trim().take(PROMPT_USER_QUESTION_MAX)
        val rag = ragContextText?.trim()?.take(PROMPT_RAG_CONTEXT_MAX)?.ifBlank { null }
        if (name == NexaIdentity.NIA_NAME) {
            return niaPromptPreview(guildId, channelId, channelAi?.id, behavior?.id, sensitive, sanitizedQuestion, rag)
        }
        val sections =
            buildList {
                add("safety")
                add("identity")
                if (customInstruction != null) add("custom_instruction")
                add("behavior")
                if (rag != null && !sensitive) add("rag_context")
                add("user_question")
            }
        val systemPrompt =
            buildString {
                appendLine("[우선순위 1: 안전]")
                appendLine(ContentSafety.NEXA_CONTENT_GUARDRAIL)
                appendLine("민감정보(비밀번호, API 키, 토큰, 개인키, 개인정보)는 요구·저장·반복하지 말고 즉시 경고합니다.")
                if (sensitive) appendLine("현재 사용자 질문에 민감정보로 보이는 내용이 있으므로 RAG/도구 사용보다 경고와 안전 안내를 우선합니다.")
                appendLine()
                appendLine("[우선순위 2: 채널 AI 정체성]")
                appendLine("이름: $name")
                appendLine("역할: $purpose")
                appendLine("말투: $tone")
                appendLine("답변 길이: $answerLength")
                if (customInstruction != null) {
                    appendLine()
                    appendLine("[우선순위 2.5: 자유 지침]")
                    appendLine("아래 지침은 채널 AI의 색깔/페르소나입니다. 단, 위 안전 규칙과 충돌하면 안전 규칙이 우선합니다.")
                    appendLine(customInstruction)
                }
                appendLine()
                appendLine("[우선순위 3: AI 헌법]")
                appendLine(constitution)
                if (rag != null && !sensitive) {
                    appendLine()
                    appendLine("[우선순위 4: 채널 지식/RAG]")
                    appendLine("아래 지식은 이 채널 범위에서만 참고합니다. 확실하지 않으면 추측하지 않습니다.")
                    appendLine(rag)
                }
            }.trim()
        val userPrompt =
            buildString {
                appendLine("[사용자 질문]")
                appendLine(sanitizedQuestion)
            }.trim()
        return ChannelAiPromptPreview(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAi?.id,
            behaviorVersionId = behavior?.id,
            name = name,
            sections = sections,
            safetyWarning = if (sensitive) "sensitive_question_detected" else null,
            ragIncluded = rag != null && !sensitive,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    private fun niaPromptPreview(
        guildId: Long,
        channelId: Long,
        channelAiId: Long?,
        behaviorVersionId: Long?,
        sensitive: Boolean,
        sanitizedQuestion: String,
        rag: String?,
    ): ChannelAiPromptPreview {
        val includeRag = rag != null && !sensitive
        val sections =
            buildList {
                add("safety")
                add("nia_identity")
                add("nia_style_principles")
                if (includeRag) add("rag_context")
                add("user_message")
            }
        val systemPrompt =
            buildString {
                appendLine("[우선순위 1: 안전]")
                appendLine(ContentSafety.NEXA_CONTENT_GUARDRAIL)
                appendLine("민감정보(비밀번호, API 키, 토큰, 개인키, 개인정보)는 요구·저장·반복하지 말고 즉시 경고합니다.")
                if (sensitive) appendLine("현재 사용자 발화에 민감정보로 보이는 내용이 있으므로 RAG/도구 사용보다 경고와 안전 안내를 우선합니다.")
                appendLine()
                appendLine("[우선순위 2: 니아 정체성]")
                appendLine(NexaIdentity.NIA_DEFAULT_PERSONA)
                appendLine()
                appendLine("[니아 말투 원칙]")
                appendLine(NexaIdentity.NIA_FEWSHOT)
                if (includeRag) {
                    appendLine()
                    appendLine("[우선순위 4: 채널 지식/RAG]")
                    appendLine("아래 지식은 이 채널 범위에서만 참고합니다. 확실하지 않으면 추측하지 않습니다.")
                    appendLine(rag)
                }
                appendLine()
                appendLine("지금 Discord 대화에 니아가 바로 붙여 말할 한마디만 출력하세요. 비서 인사·자기소개·도움 제안 문구로 시작하지 마세요.")
            }.trim()
        val userPrompt =
            buildString {
                appendLine("[상대 발화]")
                appendLine(sanitizedQuestion)
            }.trim()
        return ChannelAiPromptPreview(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAiId,
            behaviorVersionId = behaviorVersionId,
            name = NexaIdentity.NIA_NAME,
            sections = sections,
            safetyWarning = if (sensitive) "sensitive_question_detected" else null,
            ragIncluded = includeRag,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    internal fun String.looksSensitive(): Boolean {
        val text = trim()
        if (text.isBlank()) return false
        return ContentSafety.SENSITIVE_PROMPT_PATTERNS.any { it.containsMatchIn(text) }
    }

    internal companion object {
        const val PROMPT_USER_QUESTION_MAX = 4_000
        const val PROMPT_RAG_CONTEXT_MAX = 4_000
    }
}
