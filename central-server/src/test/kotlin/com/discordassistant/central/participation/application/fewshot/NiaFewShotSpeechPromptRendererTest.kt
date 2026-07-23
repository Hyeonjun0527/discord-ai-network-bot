package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaFewShotSpeechPromptRendererTest {
    @Test
    fun `published speak examples render scene and expected replies`() {
        val prompt =
            NiaFewShotSpeechPromptRenderer.renderForParticipation(
                version(
                    example(
                        expectedReplies = listOf("그건 서연이한테 말해"),
                        badReplies = listOf("뭐가 궁금한데 ㅋㅋ"),
                    ),
                ),
            )

        assertThat(prompt)
            .contains(
                "서연아 내 고민 좀 들어줘",
                "좋은 니아 답변: 그건 서연이한테 말해",
                "피해야 할 니아 답변: 뭐가 궁금한데 ㅋㅋ",
            ).doesNotContain("알고리즘 질문이 구술시험처럼 이어진 장면")
    }

    @Test
    fun `participation-only managed examples do not silently restore code speech examples`() {
        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(version(example(expectedReplies = emptyList())))

        assertThat(prompt)
            .doesNotContain("서연아 내 고민 좀 들어줘")
            .doesNotContain("다익스트라 알고리즘 말해봐")
            .contains("니아 대화 대조 예시")
    }

    @Test
    fun `baseline examples are available without an admin version`() {
        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(null)

        assertThat(prompt).contains(
            "관리자 예시는 해당 서버의 말투와 밈에 우선",
            "플로이드 워셜은 모든 정점 사이 거리를 한 번에 갱신하는 방식이야",
            "들켰네 ㅋㅋ 그래도 이렇게 말 잘 통하는 AI면 꽤 괜찮지 않냐",
            "아니야 나 진짜 사람이야",
        )
    }

    @Test
    fun `managed-only renderer does not inject participation baselines into ask mode`() {
        assertThat(NiaFewShotSpeechPromptRenderer.render(null)).isNull()
        assertThat(NiaFewShotSpeechPromptRenderer.render(version(example(expectedReplies = emptyList())))).isNull()
    }

    @Test
    fun `retrieved renderer adds only the top two speak examples without global baselines`() {
        val first = example(expectedReplies = listOf("첫 장면 답변")).copy(title = "첫 장면")
        val ignored =
            example(expectedReplies = emptyList()).copy(
                title = "침묵 장면",
                expectedAction = NiaFewShotAction.IGNORE,
            )
        val second = example(expectedReplies = listOf("둘째 장면 답변")).copy(title = "둘째 장면")
        val third = example(expectedReplies = listOf("셋째 장면 답변")).copy(title = "셋째 장면")

        val prompt = NiaFewShotSpeechPromptRenderer.renderRetrieved(listOf(first, ignored, second, third))

        assertThat(prompt)
            .contains("현재 장면과 가까운 대화 RAG", "첫 장면 답변", "둘째 장면 답변")
            .doesNotContain("셋째 장면 답변", "다익스트라 알고리즘 말해봐")
    }

    @Test
    fun `prompt budget skips an oversized complete example instead of cutting it mid sentence`() {
        val oversized =
            example(expectedReplies = listOf("관리자 답변")).copy(
                title = "oversized-managed-example",
                rawMessages =
                    (1..8).map { index ->
                        NiaFewShotRawMessage("m$index", "member", index.toLong(), "x".repeat(4_000))
                    },
                evidenceRefs = setOf("m1"),
            )

        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(version(oversized))

        assertThat(prompt)
            .hasSizeLessThanOrEqualTo(16_000)
            .doesNotContain("oversized-managed-example")
            .contains("알고리즘 질문이 구술시험처럼 이어진 장면")
    }

    private fun example(
        expectedReplies: List<String>,
        badReplies: List<String> = emptyList(),
    ) = NiaFewShotExample(
        title = "다른 사람에게 하는 말",
        rawMessages = listOf(NiaFewShotRawMessage("m1", "member", 0, "서연아 내 고민 좀 들어줘")),
        expectedAction = NiaFewShotAction.SPEAK,
        expectedReplies = expectedReplies,
        badReplies = badReplies,
        reason = "서버 밈에 맞는 응답",
        evidenceRefs = setOf("m1"),
        badAlternative = NiaFewShotBadAlternative(NiaFewShotAction.WAIT, "이 예시에서는 응답이 기준이다"),
    )

    private fun version(example: NiaFewShotExample) =
        NiaFewShotVersion(
            id = 1,
            setId = 1,
            version = 1,
            status = NiaFewShotVersionStatus.ACTIVE,
            examples = listOf(example),
            createdBy = null,
            reviewedBy = null,
            publishedAt = Instant.EPOCH,
            rollbackOfVersion = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
