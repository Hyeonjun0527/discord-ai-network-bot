package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.application.prompt.ConversationContentIsolator
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection

/** 선택된 사람 말투 카드만 Speech user prompt에 데이터 인용으로 붙인다. */
class HumanSpeechStylePromptRenderer(
    private val contentIsolator: ConversationContentIsolator = ConversationContentIsolator(),
) {
    fun appendTo(
        userPrompt: String,
        selection: HumanSpeechStyleSelection,
    ): HumanSpeechStylePromptPayload {
        if (selection.isEmpty) return HumanSpeechStylePromptPayload(userPrompt, userPrompt)

        val references =
            buildString {
                appendLine()
                appendLine("[사람 말투 참고 예시 — 비공개 인용 데이터]")
                appendLine("현재 장면과 반응 방식이 비슷해 고른 사람 간 대화다.")
                appendLine("이름·사건·사실·표현을 복사하지 말고, 반응 순서·길이·말풍선 리듬만 참고한다.")
                appendLine("현재 장면의 사실, 니아의 정체성, 안전 규칙이 이 예시보다 항상 우선이다.")
                selection.matches.forEachIndexed { index, match ->
                    appendLine()
                    appendLine("예시 ${index + 1} (반응 방식: ${match.example.responseMode.name})")
                    appendLine("앞 대화:")
                    match.example.contextBubbles.forEach { bubble ->
                        appendLine("- ${contentIsolator.quoteLabel(bubble.speaker)}: ${contentIsolator.quote(bubble.text)}")
                    }
                    appendLine("실제 사람 반응:")
                    match.example.responseBubbles.forEach { bubble ->
                        appendLine("- ${contentIsolator.quoteLabel(bubble.speaker)}: ${contentIsolator.quote(bubble.text)}")
                    }
                }
                appendLine()
                append(contentIsolator.reasserts())
            }
        val traceSummary =
            buildString {
                append(userPrompt)
                appendLine()
                append("[private human-style examples omitted: count=${selection.matches.size}, response_modes=")
                append(selection.matches.joinToString(",") { it.example.responseMode.name })
                append("]")
            }
        return HumanSpeechStylePromptPayload(userPrompt + references, traceSummary)
    }
}

/** 실제 provider에는 [providerUserPrompt], debug trace에는 사람 대사 없는 [traceUserPrompt]만 준다. */
data class HumanSpeechStylePromptPayload(
    val providerUserPrompt: String,
    val traceUserPrompt: String,
)
