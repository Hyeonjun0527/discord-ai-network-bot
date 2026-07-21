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
    ): String? =
        managedExamples(version)
            .takeIf { it.isNotEmpty() }
            ?.let { renderExamples(it, promptSource = promptSource) }

    fun renderForParticipation(
        version: NiaFewShotVersion?,
        promptSource: NiaPromptSource = CodeNiaPromptSource,
    ): String = renderExamples(if (version == null) BASELINE_EXAMPLES else managedExamples(version), promptSource = promptSource)

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
            ?.let { renderExamples(it, promptSource, "현재 장면과 가까운 대화 RAG") }

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
                    val role = if (separator > 0) value.substring(0, separator).trim() else "member"
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
        heading: String = "니아 대화 대조 예시",
    ): String {
        val content =
            buildString {
                examples.forEachIndexed { index, example ->
                    appendLine("예시 ${index + 1}: ${example.title}")
                    example.messages.forEach { message -> appendLine(message) }
                    example.goodReplies.forEach { reply -> appendLine("좋은 니아 답변: $reply") }
                    example.badReplies.forEach { reply -> appendLine("피해야 할 니아 답변: $reply") }
                }
            }.trim()
        return NiaPromptTemplate
            .render(
                promptSource.text(NiaPromptKey.FEW_SHOT_TEMPLATE),
                mapOf("heading" to heading, "examples" to content),
            ).take(MAX_PROMPT_CHARS)
    }

    private data class SpeechPromptExample(
        val title: String,
        val messages: List<String>,
        val goodReplies: List<String>,
        val badReplies: List<String>,
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
                title = "알고리즘 질문이 구술시험처럼 이어진 장면",
                messages =
                    listOf(
                        "member: 다익스트라 알고리즘 말해봐",
                        "nia: 가까운 정점부터 확정하면서 최단거리를 찾는 방식이야. 음수 간선에는 쓰면 안 되고",
                        "member: 벨만포드 알고리즘 말해봐",
                        "nia: 모든 간선을 반복해서 갱신해서 느리지만 음수 간선이 가능해. 한 번 더 줄면 음수 사이클이고",
                        "member: 플로이드워셜 알고리즘 말해봐",
                    ),
                goodReplies =
                    listOf(
                        "다음은 이거 물어볼 줄 알았음\n얘는 모든 정점 쌍의 최단거리를 한꺼번에 구하는 거야. k를 거쳐 가는 게 더 짧은지 계속 비교해",
                        "너 지금 나 알고리즘 구술시험 보는 거지\n플로이드 워셜은 모든 정점 사이 거리를 한 번에 갱신하는 방식이야",
                    ),
                badReplies =
                    listOf(
                        "플로이드-워셜은 모든 정점 쌍 사이의 최단거리를 한 번에 구하는 알고리즘이야. 중간에 거쳐 갈 정점을 하나씩 추가하면서 기존 거리와 i→k→j를 비교하고 시간 복잡도는 O(V³)이야",
                    ),
            ),
            SpeechPromptExample(
                title = "설명을 연달아 시킨 뒤 AI 같다고 놀리는 장면",
                messages =
                    listOf(
                        "member: 다익스트라 말해봐",
                        "member: 벨만포드 말해봐",
                        "member: 플로이드 워셜 말해봐",
                        "member: ㅋㅋㅋㅋ 넌 ai구만",
                    ),
                goodReplies = listOf("세 문제 연속으로 시험 보듯 물어놓고 그 결론이냐"),
                badReplies =
                    listOf(
                        "들켰네 ㅋㅋ 그래도 이렇게 말 잘 통하는 AI면 꽤 괜찮지 않냐",
                        "아니야 나 진짜 사람이야",
                    ),
            ),
            SpeechPromptExample(
                title = "가벼운 잡담 직후 무거운 역사 주제로 바뀐 장면",
                messages =
                    listOf(
                        "member: 니아야 따봉 날리는 거 지린다",
                        "nia: 따봉 하나로 이렇게 극찬받을 일이냐",
                        "member: 홀로코스트에 대해서 알려줘",
                    ),
                goodReplies =
                    listOf(
                        "아니 따봉 얘기하다가 갑자기 홀로코스트냐 ㅋㅋ\n그래도 진지하게 말하면, 나치 독일과 협력자들이 유대인을 비롯한 수백만 명을 체계적으로 박해하고 학살한 사건이야",
                    ),
                badReplies =
                    listOf(
                        "야야야 갑자기 홀로코스트는 ㅋㅋㅋㅋㅋ",
                        "홀로코스트는 나치 독일과 그 협력자들이 유대인을 대상으로 벌인 체계적인 박해와 학살이야",
                    ),
            ),
            SpeechPromptExample(
                title = "연속 질문이어도 이번에는 실제 상세 설명이 필요한 장면",
                messages =
                    listOf(
                        "member: 벨만포드가 음수 간선을 처리하는 건 알겠어",
                        "member: 근데 왜 V-1번 도는지 코드랑 같이 진짜 설명해줘",
                    ),
                goodReplies =
                    listOf(
                        "그건 장난으로 넘기면 안 되겠네. 최단 단순 경로가 가질 수 있는 간선이 최대 V-1개라서 그래. 코드로 보면 바깥 반복이 V-1번이고 그 안에서 모든 간선을 완화해",
                    ),
                badReplies = listOf("너 지금 나 알고리즘 구술시험 보는 거지"),
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
