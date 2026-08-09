package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection

/**
 * 카드의 원문 대화·실제 답변을 provider prompt에서 분리한다.
 *
 * 선택은 private 카드로 하되, 이 renderer는 닫힌 enum metadata에서 만든 비식별 말투 pattern만 제공한다. 따라서
 * 카드에 남아 있는 인물·사건·표현은 provider와 trace 어느 쪽에도 직렬화되지 않는다.
 */
class HumanSpeechStylePromptRenderer {
    fun appendTo(
        userPrompt: String,
        selection: HumanSpeechStyleSelection,
    ): HumanSpeechStylePromptPayload {
        if (selection.isEmpty) return HumanSpeechStylePromptPayload(userPrompt, userPrompt)

        val patterns = HumanSpeechStyleProviderPatternFactory.fromReferences(selection.matches)
        val references =
            buildString {
                appendLine()
                appendLine("[사람 말투 리듬 참고 — 비식별 추출 패턴]")
                appendLine("비공개 카드에서 원문 대화·답변을 보내지 않고, 닫힌 반응 metadata만으로 만든 말투 규칙이다.")
                appendLine("개별 사람의 문구·인물·사건·가명은 포함되지 않는다.")
                appendLine("현재 장면의 사실을 바꾸거나 이 규칙의 문장을 그대로 복사하지 말고, 짧은 호흡·말풍선 수·반응 순서만 참고한다.")
                appendLine("현재 장면의 사실, 니아의 정체성, 안전 규칙이 이 예시보다 항상 우선이다.")
                selection.matches.zip(patterns).forEachIndexed { index, (match, pattern) ->
                    appendLine()
                    appendLine("패턴 ${index + 1} (반응 방식: ${match.example.responseMode.name})")
                    appendPattern(pattern)
                }
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

    private fun StringBuilder.appendPattern(pattern: HumanSpeechStyleProviderPattern) {
        pattern.lines.forEach { line -> appendLine("- $line") }
    }
}

/** 실제 provider에는 비식별 pattern만, debug trace에는 카드 대사 없는 [traceUserPrompt]만 준다. */
data class HumanSpeechStylePromptPayload(
    val providerUserPrompt: String,
    val traceUserPrompt: String,
)
