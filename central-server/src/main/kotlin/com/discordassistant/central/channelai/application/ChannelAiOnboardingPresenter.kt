package com.discordassistant.central.channelai.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import org.springframework.stereotype.Component

/**
 * 채널 AI 온보딩 카드 합성 — 읽기 전용 협력자(@Transactional·write 없음).
 *
 * 활성/최신 behavior 를 조회해 온보딩 메시지를 구성한다. 조회만 하므로 호출자 TX 문맥에서 그대로
 * 동작한다(별 빈으로 빼도 새 TX 미발생). 사용자 노출 문구·예시 매핑은 추출 전과 1바이트 불변.
 */
@Component
class ChannelAiOnboardingPresenter(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun channelOnboarding(
        guildId: Long,
        channelId: Long,
    ): ChannelAiOnboarding {
        featureGate.requireChannelAiEnabled()
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behavior =
            channelAi?.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: channelAi?.let { versions.findTopByChannelAiIdOrderByVersionDesc(it.id) }
        val name = channelAi?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: "니아"
        val purpose = behavior?.purpose ?: DEFAULT_CHANNEL_AI_PURPOSE
        val tone = behavior?.tone ?: DEFAULT_CHANNEL_AI_TONE
        val answerLength = behavior?.answerLength ?: DEFAULT_CHANNEL_AI_ANSWER_LENGTH
        val examples = examplesForPurpose(purpose)
        val safetyNotice = "비밀번호, API 키, 토큰, 개인정보 같은 민감정보는 보내지 마세요."
        val description = "$purpose\n말투는 $tone, 답변 길이는 $answerLength 기준으로 맞춰드릴게요."
        return ChannelAiOnboarding(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAi?.id,
            name = name,
            title = "안녕하세요. 저는 이 채널의 $name 이에요.",
            description = description,
            safetyNotice = safetyNotice,
            examples = examples,
            message = onboardingMessage(name, description, safetyNotice, examples),
            empty = channelAi == null,
        )
    }

    fun examplesForPurpose(purpose: String): List<String> {
        val p = purpose.lowercase()
        return when {
            listOf("개발", "코드", "spring", "kotlin", "에러").any { it in p } ->
                listOf("이 에러가 왜 나는지 알려줘", "이 코드 리뷰해줘", "테스트 코드 만들어줘")
            listOf("번역", "영어", "문장").any { it in p } ->
                listOf("이 문장을 자연스럽게 번역해줘", "더 공손한 표현으로 바꿔줘", "영어 답장을 다듬어줘")
            listOf("회의", "요약", "회의록").any { it in p } ->
                listOf("회의 내용을 요약해줘", "결정사항과 할 일을 분리해줘", "액션아이템만 뽑아줘")
            listOf("공지", "안내", "릴리즈").any { it in p } ->
                listOf("공지 초안을 써줘", "운영진 말투로 다듬어줘", "짧은 안내문으로 바꿔줘")
            else ->
                listOf("이 내용을 쉽게 설명해줘", "핵심만 요약해줘", "다음 행동을 추천해줘")
        }
    }

    fun onboardingMessage(
        name: String,
        description: String,
        safetyNotice: String,
        examples: List<String>,
    ): String =
        buildString {
            appendLine("❂ **$name 채널 AI가 준비됐어요**")
            appendLine()
            appendLine(description)
            appendLine()
            appendLine("**질문 예시**")
            examples.forEach { appendLine("- $it") }
            appendLine()
            appendLine("⚠️ $safetyNotice")
        }.trim()
}
