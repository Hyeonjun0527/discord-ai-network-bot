package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion

object NiaFewShotSpeechPromptRenderer {
    fun render(version: NiaFewShotVersion?): String? {
        val examples =
            version
                ?.examples
                ?.asSequence()
                ?.filter { it.expectedAction == NiaFewShotAction.SPEAK && it.expectedReplies.isNotEmpty() }
                ?.sortedWith(compareByDescending<NiaFewShotExample> { it.priority }.thenBy { it.id ?: Long.MAX_VALUE })
                ?.take(MAX_SPEECH_EXAMPLES)
                ?.toList()
                .orEmpty()
        if (examples.isEmpty()) return null

        return buildString {
            appendLine("[관리자가 게시한 니아 대화 예시]")
            appendLine("정답 문장을 그대로 복사하지 말고, 비슷한 장면에서 맥락·태도·서버 내부 밈을 참고한다.")
            appendLine("피해야 할 답변은 같은 장면에서 부자연스러웠던 대조 예시다. 그 패턴을 답습하지 않는다.")
            examples.forEachIndexed { index, example ->
                appendLine()
                appendLine("예시 ${index + 1}: ${example.title}")
                example.rawMessages.forEach { message -> appendLine("${message.authorRole}: ${message.text}") }
                example.expectedReplies.forEach { reply -> appendLine("좋은 니아 답변: $reply") }
                example.badReplies.forEach { reply -> appendLine("피해야 할 니아 답변: $reply") }
            }
        }.trim().take(MAX_PROMPT_CHARS)
    }

    private const val MAX_SPEECH_EXAMPLES = 12
    private const val MAX_PROMPT_CHARS = 12_000
}
