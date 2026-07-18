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
        val prompt = NiaFewShotSpeechPromptRenderer.render(version(example(expectedReplies = listOf("그건 서연이한테 말해 ㅋㅋ"))))

        assertThat(prompt).contains("서연아 내 고민 좀 들어줘", "니아: 그건 서연이한테 말해 ㅋㅋ")
    }

    @Test
    fun `participation-only examples do not enter speech prompt`() {
        assertThat(NiaFewShotSpeechPromptRenderer.render(version(example(expectedReplies = emptyList())))).isNull()
    }

    private fun example(expectedReplies: List<String>) =
        NiaFewShotExample(
            title = "다른 사람에게 하는 말",
            rawMessages = listOf(NiaFewShotRawMessage("m1", "member", 0, "서연아 내 고민 좀 들어줘")),
            expectedAction = NiaFewShotAction.SPEAK,
            expectedReplies = expectedReplies,
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
