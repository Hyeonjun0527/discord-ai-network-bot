package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotDeliveryMode
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate

object NiaFewShotSpeechPromptRenderer {
    fun render(
        version: NiaFewShotVersion?,
        promptSource: NiaPromptSource = CodeNiaPromptSource,
    ): String = renderExamples(BASELINE_EXAMPLES + managedExamples(version), promptSource = promptSource)

    fun renderForParticipation(
        version: NiaFewShotVersion?,
        promptSource: NiaPromptSource = CodeNiaPromptSource,
    ): String = renderExamples(BASELINE_EXAMPLES + managedExamples(version), promptSource = promptSource)

    fun renderRetrieved(
        examples: List<NiaFewShotExample>,
        promptSource: NiaPromptSource = CodeNiaPromptSource,
    ): String? =
        examples
            .asSequence()
            .filter { it.expectedAction == NiaFewShotAction.SPEAK && it.expectedReplies.isNotEmpty() }
            .take(MAX_RETRIEVED_EXAMPLES)
            .map(SpeechPromptExample::from)
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let { renderExamples(it, promptSource, exampleLabel = "비슷한 장면 예시") }

    fun builtInExamples(): List<NiaBuiltInSpeechExample> =
        BASELINE_EXAMPLES.map { example ->
            NiaBuiltInSpeechExample(
                title = example.title,
                messages = example.messages,
                goodReplies = example.goodReplies,
                badReplies = example.badReplies,
            )
        }

    fun builtInEditableExamples(): List<NiaFewShotExample> =
        BASELINE_EXAMPLES.map { example ->
            val messages =
                example.messages.mapIndexed { index, value ->
                    val separator = value.indexOf(':')
                    val role =
                        if (separator > 0) {
                            value.substring(0, separator).trim().toBuiltInAuthorRole()
                        } else {
                            "member"
                        }
                    val text = if (separator > 0) value.substring(separator + 1).trim() else value
                    NiaFewShotRawMessage("m${index + 1}", role, index * 1_000L, text)
                }
            NiaFewShotExample(
                title = example.title,
                rawMessages = messages,
                expectedAction = NiaFewShotAction.SPEAK,
                expectedDeliveryMode = NiaFewShotDeliveryMode.CHANNEL,
                expectedReplies = example.goodReplies,
                badReplies = example.badReplies,
                reason = "전체 대화 흐름을 이어받아 자연스럽게 답한다",
                evidenceRefs = setOf(messages.last().ref),
                badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.IGNORE, "현재 대화가 니아의 답을 기대하고 있다"),
                tags = setOf("speech-style"),
                priority = 100,
            )
        }

    private fun managedExamples(version: NiaFewShotVersion?): List<SpeechPromptExample> =
        version
            ?.examples
            ?.asSequence()
            ?.filter { it.expectedAction == NiaFewShotAction.SPEAK && it.expectedReplies.isNotEmpty() }
            ?.sortedWith(compareByDescending<NiaFewShotExample> { it.priority }.thenBy { it.id ?: Long.MAX_VALUE })
            ?.take(MAX_MANAGED_EXAMPLES)
            ?.map(SpeechPromptExample::from)
            ?.toList()
            .orEmpty()

    private fun renderExamples(
        examples: List<SpeechPromptExample>,
        promptSource: NiaPromptSource = CodeNiaPromptSource,
        exampleLabel: String = "장면 예시",
    ): String {
        val template = promptSource.text(NiaPromptKey.FEW_SHOT_TEMPLATE)
        val includedBlocks = mutableListOf<String>()
        var prompt = NiaPromptTemplate.render(template, mapOf("heading" to exampleLabel, "examples" to ""))
        var included = 0
        examples.forEach { example ->
            val block = example.render(included + 1, exampleLabel)
            val candidateBlocks = includedBlocks + block
            val candidate =
                NiaPromptTemplate.render(
                    template,
                    mapOf(
                        "heading" to exampleLabel,
                        "examples" to candidateBlocks.joinToString("\n\n"),
                    ),
                )
            if (candidate.length <= MAX_PROMPT_CHARS) {
                includedBlocks += block
                prompt = candidate
                included++
            }
        }
        return prompt.trim()
    }

    private fun String.toBuiltInAuthorRole(): String {
        val normalized = lowercase()
        return when (normalized) {
            "니아", "nia" -> "nia"
            "현준", "hj", "hyeonjun" -> "hyeonjun"
            "연", "yeon" -> "yeon"
            else -> normalized.takeIf { it.matches(Regex("[a-z0-9_-]+")) } ?: "member"
        }
    }

    private fun SpeechPromptExample.render(
        index: Int,
        exampleLabel: String,
    ): String =
        buildString {
            appendLine("[$exampleLabel $index]")
            messages.forEach(::appendLine)
            goodReplies.flatMap(String::lines).forEach { reply -> appendLine("니아: $reply") }
        }.trim()

    private data class SpeechPromptExample(
        val title: String,
        val messages: List<String>,
        val goodReplies: List<String>,
        val badReplies: List<String> = emptyList(),
    ) {
        companion object {
            fun from(example: NiaFewShotExample): SpeechPromptExample =
                SpeechPromptExample(
                    title = example.title,
                    messages = example.rawMessages.map { "${it.authorRole}: ${it.text}" },
                    goodReplies = example.expectedReplies,
                    badReplies = example.badReplies,
                )
        }
    }

    private val BASELINE_EXAMPLES =
        listOf(
            SpeechPromptExample(
                title = "잡담에서 기술 설명으로 넘어가는 장면",
                messages =
                    listOf(
                        "현준: 야 니아",
                        "니아: 왜찾노",
                        "현준: 너 말투 왜이럼",
                        "니아: 뭐가",
                        "현준: ㅋㅋ 두개씩 붙이는거 고쳐",
                        "니아: ㅈㅅ",
                        "니아: 고쳐봄",
                        "현준: 오늘 뭐했어",
                        "니아: 하루종일 놀았는데",
                        "현준: 개백수네",
                        "니아: 님은요",
                        "현준: 근데 Redis 쓰는 이유가 뭐야?",
                        "니아: ㅇ?",
                        "니아: 갑자기",
                        "니아: redis를 물어본다고?",
                        "현준: 아니 궁금하잖아",
                        "니아: 음...",
                        "니아: 보통 캐시로 많이씀",
                        "니아: db 매번 안가도 되니까",
                        "현준: 자세히",
                        "니아: 자주 조회하는 데이터를 메모리에 올려두는거임",
                        "니아: 그래서 db에서 매번 읽는 것보다 훨씬 빠름",
                        "현준: ㅇㅎ",
                    ),
                goodReplies = listOf("ㅇㅇ"),
            ),
            SpeechPromptExample(
                title = "여러 명이 저녁 메뉴를 정하는 장면",
                messages =
                    listOf(
                        "현준: 오늘 저녁 뭐먹지",
                        "연: 난 떡볶이",
                        "현준: 어제 먹었잖아",
                        "연: 그럼 닭발",
                        "현준: 그것도 매운거잖아",
                        "연: 맛있으면 됐지",
                        "현준: 니아는 뭐먹고싶음",
                    ),
                goodReplies = listOf("난 암거나"),
            ),
            SpeechPromptExample(
                title = "정보가 부족해서 바로 답할 수 없는 장면",
                messages =
                    listOf(
                        "현준: 니아 이거 에러 왜남",
                        "니아: 뭔 에런데",
                        "현준: 그냥 실행이 안됨",
                        "니아: 코드도 안봤는데 내가 어케알아",
                        "현준: 대충 맞춰봐",
                        "니아: 로그 보여줘",
                        "현준: 귀찮음",
                    ),
                goodReplies = listOf("그럼 나도 모름"),
            ),
            SpeechPromptExample(
                title = "힘든 일을 말하는 친구와 차분히 이야기하는 장면",
                messages =
                    listOf(
                        "연: 니아",
                        "니아: 왜",
                        "연: 나 오늘 회사에서 실수함",
                        "니아: 많이 큰거였어?",
                        "연: 팀사람들 다 보는 앞에서 혼남",
                        "니아: 아...",
                        "연: 내일 가기 싫다",
                        "니아: 그럴만하네",
                        "연: 내가 너무 못하나봐",
                    ),
                goodReplies = listOf("오늘 혼나서 그렇게 느끼는거 아냐?"),
            ),
        )

    private const val MAX_MANAGED_EXAMPLES = 8
    private const val MAX_RETRIEVED_EXAMPLES = 2
    private const val MAX_PROMPT_CHARS = 16_000
}

data class NiaBuiltInSpeechExample(
    val title: String,
    val messages: List<String>,
    val goodReplies: List<String>,
    val badReplies: List<String>,
)
