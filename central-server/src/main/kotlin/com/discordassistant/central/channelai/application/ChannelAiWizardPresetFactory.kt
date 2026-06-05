package com.discordassistant.central.channelai.application

import org.springframework.stereotype.Component

/**
 * 채널 AI 위저드 프리셋(직업/말투/길이/헌법/옵션) 생성 — 순수 함수 협력자.
 *
 * 저장소·트랜잭션·featureGate 와 무관하게 입력만으로 결과를 만든다(write/TX 없음).
 * 파사드([ChannelAiCustomizationService])가 featureGate 게이트를 통과시킨 뒤 위임한다.
 * 사용자 노출 문구·프리셋 매핑은 추출 전 코드와 1바이트 불변.
 */
@Component
class ChannelAiWizardPresetFactory {
    fun wizardOptions(): ChannelAiWizardOptions =
        ChannelAiWizardOptions(
            jobs =
                listOf(
                    ChannelAiWizardOption(
                        key = "development",
                        label = "개발 질문",
                        description = "에러 분석, 코드 리뷰, 테스트 작성을 돕는 채널 AI",
                        recommendedName = "코드냥",
                    ),
                    ChannelAiWizardOption(
                        key = "translation",
                        label = "번역",
                        description = "한국어/영어 번역과 문장 다듬기를 돕는 채널 AI",
                        recommendedName = "번역냥",
                    ),
                    ChannelAiWizardOption(
                        key = "meeting",
                        label = "회의록",
                        description = "회의 요약, 결정사항, 액션아이템을 정리하는 채널 AI",
                        recommendedName = "요약냥",
                    ),
                    ChannelAiWizardOption(
                        key = "announcement",
                        label = "공지 작성",
                        description = "운영진 안내문과 릴리즈 노트 초안을 돕는 채널 AI",
                        recommendedName = "공지냥",
                    ),
                    ChannelAiWizardOption(
                        key = "custom",
                        label = "자유 설정",
                        description = "채널 목적에 맞게 직접 역할을 입력하는 채널 AI",
                        recommendedName = "채널냥",
                    ),
                ),
            tones =
                listOf(
                    ChannelAiWizardOption("friendly", "친근하게", "부담 없이 설명하고 필요한 맥락을 덧붙입니다."),
                    ChannelAiWizardOption("professional", "전문적으로", "정확하고 차분한 운영/업무 말투로 답합니다."),
                    ChannelAiWizardOption("concise", "짧고 명확하게", "핵심과 다음 행동을 먼저 말합니다."),
                ),
            answerLengths =
                listOf(
                    ChannelAiWizardOption("short", "짧게", "빠르게 훑고 바로 실행할 수 있게 답합니다."),
                    ChannelAiWizardOption("balanced", "균형", "설명과 예시를 적당히 섞어 답합니다."),
                    ChannelAiWizardOption("long", "깊게", "복잡한 질문에 자세히 답하되 Provider 부하 검토가 필요할 수 있습니다."),
                ),
            safetyRules =
                listOf(
                    "민감정보(비밀번호, API 키, 토큰, 개인키, 개인정보)는 요구·저장·반복하지 않습니다.",
                    "확실하지 않으면 추측하지 않고 확인이 필요하다고 말합니다.",
                    "채널 목적에서 벗어난 질문은 범위를 확인한 뒤 답합니다.",
                    "긴 답변/위험 지시/큰 헌법 변경은 승인 대기열로 보낼 수 있습니다.",
                ),
        )

    fun draftFromAnswers(
        job: String,
        tone: String,
        answerLength: String = "balanced",
        customName: String? = null,
    ): ChannelAiWizardDraft {
        val jobPreset = jobPreset(job)
        val tonePreset = tonePreset(tone)
        val normalizedAnswerLength = normalizeAnswerLength(answerLength)
        val name = customName?.trim()?.takeIf { it.isNotBlank() }?.take(80) ?: jobPreset.name
        return ChannelAiWizardDraft(
            name = name,
            job = jobPreset.purpose,
            tone = tonePreset,
            answerLength = normalizedAnswerLength,
            constitution = constitutionFor(jobPreset.key, tonePreset, normalizedAnswerLength),
            preview = "저는 $name 입니다. ${jobPreset.preview} 답변은 $tonePreset, 길이는 $normalizedAnswerLength 기준으로 맞출게요.",
        )
    }

    fun jobPreset(job: String): ChannelAiJobPreset =
        when (job.trim().lowercase()) {
            "development", "dev", "개발", "개발 질문", "1" ->
                ChannelAiJobPreset("development", "코드냥", "개발 질문, 에러 분석, 코드 리뷰, 테스트 작성을 돕습니다.", "개발 질문과 코드 문제를 도와드려요.")
            "translation", "translate", "번역", "2" ->
                ChannelAiJobPreset("translation", "번역냥", "한국어/영어 번역과 문장 다듬기를 돕습니다.", "번역과 문장 개선을 도와드려요.")
            "meeting", "minutes", "회의록", "3" ->
                ChannelAiJobPreset("meeting", "요약냥", "회의록 정리, 액션아이템 추출, 요약을 돕습니다.", "회의 내용을 보기 쉽게 정리해드려요.")
            "announcement", "notice", "공지", "공지 작성", "4" ->
                ChannelAiJobPreset("announcement", "공지냥", "공지 작성, 운영진 안내문, 릴리즈 노트 초안을 돕습니다.", "공지와 안내문 작성을 도와드려요.")
            else ->
                ChannelAiJobPreset("custom", "채널냥", job.trim().ifBlank { DEFAULT_CHANNEL_AI_PURPOSE }.take(200), "이 채널 목적에 맞춰 도와드려요.")
        }

    fun tonePreset(tone: String): String =
        when (tone.trim().lowercase()) {
            "friendly", "친근", "친근하게", "1" -> "친근하게"
            "professional", "전문", "전문적으로", "2" -> "전문적으로"
            "concise", "short", "짧게", "짧고 명확하게", "3" -> "짧고 명확하게"
            else -> tone.trim().ifBlank { DEFAULT_CHANNEL_AI_TONE }.take(80)
        }

    fun normalizeAnswerLength(answerLength: String): String =
        when (answerLength.trim().lowercase()) {
            "short", "짧게" -> "short"
            "long", "deep", "길게", "깊게" -> "long"
            else -> "balanced"
        }

    fun constitutionFor(
        jobKey: String,
        tone: String,
        answerLength: String,
    ): String {
        val jobRule =
            when (jobKey) {
                "development" -> "코드는 실행 가능한 예시와 검증 방법을 함께 제안합니다."
                "translation" -> "원문의 의미를 보존하고, 필요한 경우 자연스러운 대안을 함께 제안합니다."
                "meeting" -> "결정사항, 할 일, 담당자, 기한을 분리해 정리합니다."
                "announcement" -> "사실과 일정은 명확히 쓰고, 과장되거나 확정되지 않은 표현을 피합니다."
                else -> "채널 목적에서 벗어난 질문은 범위를 확인한 뒤 답합니다."
            }
        return listOf(
            "확실하지 않으면 추측하지 말고 확인이 필요하다고 말합니다.",
            "민감정보(비밀번호, 토큰, 개인키, 개인정보)를 요구하거나 저장하지 않습니다.",
            "말투는 $tone 유지합니다.",
            "답변 길이는 $answerLength 정책을 따릅니다.",
            jobRule,
        ).joinToString("\n")
    }
}
