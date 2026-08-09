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
    fun `managed speak examples follow the global scenes without labels or instructions`() {
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
                "[장면 예시 1]",
                "현준: 야 니아",
                "서연아 내 고민 좀 들어줘",
                "니아: 그건 서연이한테 말해",
            ).doesNotContain(
                "좋은 니아 답변",
                "피해야 할 니아 답변",
                "문장을 그대로 복사하지 말고",
                "뭐가 궁금한데 ㅋㅋ",
            )
    }

    @Test
    fun `participation-only managed examples still keep the global scenes`() {
        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(version(example(expectedReplies = emptyList())))

        assertThat(prompt)
            .doesNotContain("서연아 내 고민 좀 들어줘")
            .contains("현준: 야 니아", "니아: ㅇㅇ")
    }

    @Test
    fun `baseline examples are available without an admin version`() {
        val prompt = NiaFewShotSpeechPromptRenderer.renderForParticipation(null)

        assertThat(prompt)
            .contains(
                "[장면 예시 1]",
                "현준: ㅋㅋ 두개씩 붙이는거 고쳐",
                "니아: ㅈㅅ",
                "니아: 음...",
                "[장면 예시 4]",
                "니아: 오늘 혼나서 그렇게 느끼는거 아냐?",
            ).doesNotContain("좋은 니아 답변", "피해야 할 니아 답변", "문장을 그대로 복사하지 말고")
    }

    @Test
    fun `ask mode uses the same baseline speech examples when no admin version exists`() {
        assertThat(NiaFewShotSpeechPromptRenderer.render(null))
            .contains("[장면 예시 1]", "현준: 야 니아", "니아: ㅇㅇ")
        assertThat(NiaFewShotSpeechPromptRenderer.render(version(example(expectedReplies = emptyList()))))
            .contains("[장면 예시 1]", "현준: 야 니아", "니아: ㅇㅇ")
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
            .contains(
                "[비슷한 장면 예시 1]",
                "니아: 첫 장면 답변",
                "[비슷한 장면 예시 2]",
                "니아: 둘째 장면 답변",
            ).doesNotContain("셋째 장면 답변", "현준: 야 니아", "좋은 니아 답변")
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
            .doesNotContain("관리자 답변", "x".repeat(4_000))
            .contains("[장면 예시 1]", "현준: 야 니아")
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
